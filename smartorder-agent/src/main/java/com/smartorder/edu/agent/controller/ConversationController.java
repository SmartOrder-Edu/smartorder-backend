package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import com.smartorder.edu.agent.model.vo.MessageVO;
import com.smartorder.edu.agent.service.ConversationContextService;
import com.smartorder.edu.agent.service.ConversationService;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话历史", description = "对话会话与消息查询接口")
public class ConversationController {

    private final ConversationMessageMapper messageMapper;
    private final ConversationContextService contextService;
    private final ConversationService conversationService;

    @GetMapping("/conversations")
    @Operation(summary = "获取用户对话列表",
               description = "直接查会话主表，按置顶 + 最近活跃时间倒序；不再聚合消息表")
    public Result<List<ConversationVO>> getConversations() {
        Long userId = getCurrentUserId();
        return Result.success(conversationService.listConversations(userId));
    }

    @GetMapping("/conversations/{conversationId}")
    @Operation(summary = "获取会话详情", description = "返回会话标题、状态、消息数、最后活跃时间等")
    public Result<ConversationVO> getConversation(@PathVariable String conversationId) {
        Long userId = getCurrentUserId();
        return Result.success(conversationService.getConversation(conversationId, userId));
    }

    @PatchMapping("/conversations/{conversationId}")
    @Operation(summary = "会话改名",
               description = "改名后 title_source=USER，异步 LLM 标题任务不再覆盖")
    public Result<String> renameConversation(@PathVariable String conversationId,
                                             @RequestBody Map<String, String> body) {
        Long userId = getCurrentUserId();
        conversationService.rename(conversationId, userId, body != null ? body.get("title") : null);
        return Result.success("会话标题已更新");
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "获取对话消息",
               description = "支持游标分页（beforeSequence）与旧版 OFFSET 分页（page/size）；按会话内序号正序返回")
    public Result<Map<String, Object>> getMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer beforeSequence,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = getCurrentUserId();

        // 软删会话后，所有消息入口统一在这里被挡住
        conversationService.assertActive(conversationId, userId);

        if (size > 500) size = 500;
        if (size < 1) size = 1;

        List<ConversationMessage> entities;
        boolean hasMore;
        Integer nextBeforeSequence;

        if (beforeSequence != null) {
            // 游标分页：取更早的一页，多取 1 条用于判断 hasMore
            List<ConversationMessage> desc = messageMapper.selectMessagesBefore(
                    conversationId, userId, beforeSequence, size + 1);
            hasMore = desc.size() > size;
            if (hasMore) {
                desc = desc.subList(0, size);
            }
            entities = new ArrayList<>(desc);
            // mapper 按 sequence_no DESC 返回，反转成正序
            Collections.reverse(entities);
        } else {
            // 旧版 OFFSET 分页（兼容未升级的前端）
            if (page < 1) page = 1;
            int offset = (page - 1) * size;
            entities = messageMapper.selectMessagesPage(conversationId, userId, size, offset);
            Integer firstSeq = entities.isEmpty() ? null : entities.get(0).getSequenceNo();
            hasMore = firstSeq != null && firstSeq > 1;
        }

        nextBeforeSequence = entities.isEmpty() ? null : entities.get(0).getSequenceNo();

        List<MessageVO> voList = entities.stream().map(this::toMessageVO).toList();

        int total = resolveTotal(conversationId, userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", voList);
        payload.put("total", total);
        payload.put("page", page);
        payload.put("size", size);
        payload.put("hasMore", hasMore);
        payload.put("nextBeforeSequence", nextBeforeSequence);
        return Result.success(payload);
    }

    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "删除对话",
               description = "软删会话（status=DELETED）+ 软删消息与摘要，硬删追踪事件；消息数据保留但不再可访问")
    public Result<String> deleteConversation(@PathVariable String conversationId) {
        Long userId = getCurrentUserId();
        contextService.deleteConversation(conversationId, userId);
        return Result.success("对话删除成功");
    }

    // ============ Private ============

    private MessageVO toMessageVO(ConversationMessage e) {
        MessageVO vo = new MessageVO();
        vo.setId(e.getId());
        vo.setRole(e.getRole());
        vo.setContent(e.getContent());
        vo.setToolName(e.getToolName());
        vo.setTraceId(e.getTraceId());
        vo.setReasoningContent(e.getReasoningContent());
        vo.setReasoningSegments(e.getMetadataJson());
        vo.setAttachmentFileIds(e.getAttachmentFileIds());
        vo.setCreateTime(e.getCreateTime());
        // v2 字段
        vo.setPublicId(e.getPublicId());
        vo.setSequenceNo(e.getSequenceNo());
        vo.setStatus(e.getStatus());
        vo.setParentId(e.getParentMessageId());
        vo.setAttemptNo(e.getAttemptNo());
        vo.setIsVisible(e.getIsVisible());
        vo.setStartedAt(e.getStartedAt());
        vo.setErrorMessage(e.getErrorMessage());
        return vo;
    }

    /**
     * total 优先取会话主表的 message_count（免 COUNT(*)），主表行缺失时退回计数查询。
     */
    private int resolveTotal(String conversationId, Long userId) {
        try {
            ConversationVO detail = conversationService.getConversation(conversationId, userId);
            if (detail.getMessageCount() != null) {
                return detail.getMessageCount();
            }
        } catch (Exception e) {
            log.debug("[Conversation] 取会话详情失败，退回计数查询: cid={}, reason={}",
                    conversationId, e.getMessage());
        }
        return messageMapper.countByConversationId(conversationId, userId);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
