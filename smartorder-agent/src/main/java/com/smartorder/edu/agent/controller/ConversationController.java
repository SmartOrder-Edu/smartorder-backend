package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import com.smartorder.edu.agent.model.vo.MessageVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 对话历史", description = "对话会话与消息查询接口")
public class ConversationController {

    private final ConversationMessageMapper messageMapper;

    @GetMapping("/conversations")
    @Operation(summary = "获取用户对话列表", description = "返回当前用户的所有对话会话，按最近活跃时间倒序")
    public Result<List<ConversationVO>> getConversations() {
        Long userId = getCurrentUserId();
        List<ConversationVO> list = messageMapper.selectUserConversations(userId);
        return Result.success(list);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "获取对话消息", description = "分页返回指定对话的消息记录，按时间正序")
    public Result<Map<String, Object>> getMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {

        Long userId = getCurrentUserId();

        if (size > 100) size = 100;
        if (page < 1) page = 1;
        int offset = (page - 1) * size;

        int total = messageMapper.countByConversationId(conversationId, userId);
        List<ConversationMessage> entities = messageMapper.selectMessagesPage(conversationId, userId, size, offset);

        List<MessageVO> voList = entities.stream().map(e -> {
            MessageVO vo = new MessageVO();
            vo.setId(e.getId());
            vo.setRole(e.getRole());
            vo.setContent(e.getContent());
            vo.setToolName(e.getToolName());
            vo.setTraceId(e.getTraceId());
            vo.setReasoningContent(e.getReasoningContent());
            vo.setReasoningSegments(e.getMetadataJson());
            vo.setCreateTime(e.getCreateTime());
            return vo;
        }).toList();

        return Result.success(Map.of(
                "list", voList,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
