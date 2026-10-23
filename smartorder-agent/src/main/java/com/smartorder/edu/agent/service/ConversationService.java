package com.smartorder.edu.agent.service;

import cn.hutool.core.util.IdUtil;
import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.vo.ConversationVO;
import com.smartorder.edu.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话主表读写服务。
 * <p>
 * 职责边界：
 * - 列表 / 详情 / 改名 / 软删 / 状态校验
 * - 兜底补建（迁移遗漏或并发窗口导致主表行缺失时，就地补一行）
 * <p>
 * 不负责"轮次写入"（那是 {@link ConversationTurnService} 的事），避免事务边界混乱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /** 列表单页上限，防止一次拉太多 */
    private static final int MAX_LIST_SIZE = 200;
    private static final int TITLE_MAX = 200;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;

    /**
     * 会话列表——直接查主表（单表索引扫描，不再聚合消息表）。
     * <p>
     * 两级兜底：
     * 1) 主表为空 → 从消息表反推并补建主表行；
     * 2) 主表不可用（未执行迁移，表不存在）→ 回退到旧的聚合查询，保证旧环境仍能跑。
     */
    public List<ConversationVO> listConversations(Long userId) {
        try {
            List<ConversationVO> list = conversationMapper.selectPageByUser(userId, MAX_LIST_SIZE, 0);
            if (!list.isEmpty()) {
                return list;
            }
            return backfillAndList(userId);
        } catch (Exception e) {
            log.warn("[Conversation] 会话主表不可用，回退到消息表聚合（请确认已执行 db_migration_conversation.sql）: {}",
                    e.getMessage());
            return messageMapper.selectUserConversations(userId);
        }
    }

    public ConversationVO getConversation(String publicId, Long userId) {
        ConversationVO vo = conversationMapper.selectVoByPublicId(publicId, userId);
        if (vo == null) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return vo;
    }

    /**
     * 改名。改名后 title_source=USER，异步 LLM 标题任务不再覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rename(String publicId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException("会话标题不能为空");
        }
        String trimmed = title.trim();
        if (trimmed.length() > TITLE_MAX) {
            trimmed = trimmed.substring(0, TITLE_MAX);
        }
        int updated = conversationMapper.updateTitle(publicId, userId, trimmed);
        if (updated == 0) {
            throw new BusinessException("会话不存在、已删除或无权操作");
        }
        log.info("[Conversation] 会话改名: publicId={}, title={}", publicId, trimmed);
    }

    /**
     * 软删会话（消息不删，符合设计；所有消息入口靠 assertActive 兜住）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(String publicId, Long userId) {
        int updated = conversationMapper.softDelete(publicId, userId);
        log.info("[Conversation] 软删会话: publicId={}, affected={}", publicId, updated);
    }

    /**
     * 补建会话主表行（幂等）。标题优先取传入的提示，否则用"新对话"。
     */
    @Transactional(rollbackFor = Exception.class)
    public Conversation createIfAbsent(String publicId, Long userId, String titleHint) {
        Conversation existing = conversationMapper.selectByPublicId(publicId);
        if (existing != null) {
            return existing;
        }
        Conversation conversation = new Conversation();
        // 主键由应用侧生成（雪花），不走数据库自增——多实例/迁移场景下自增会冲突
        conversation.setId(IdUtil.getSnowflakeNextId());
        conversation.setPublicId(publicId);
        conversation.setUserId(userId);
        conversation.setTitle(truncate(blankToNull(titleHint) == null ? "新对话" : titleHint.trim(), TITLE_MAX));
        conversation.setTitleSource(Conversation.TITLE_SOURCE_AUTO);
        conversation.setStatus(Conversation.STATUS_ACTIVE);
        conversation.setMessageCount(0);
        conversation.setPinned(0);
        conversation.setLastMessageAt(LocalDateTime.now());
        try {
            conversationMapper.insert(conversation);
            return conversation;
        } catch (DuplicateKeyException e) {
            // 并发补建：另一线程已插入同一 public_id
            Conversation conflict = conversationMapper.selectByPublicId(publicId);
            if (conflict != null) {
                return conflict;
            }
            // 查不到同 public_id 的行，说明冲突的是主键（雪花 ID 重复，极罕见：
            // 多实例派生出相同 workerId 时才会发生）。换一个 ID 重试一次。
            log.warn("[Conversation] 主键冲突且未查到同 publicId 的会话，换 ID 重试: publicId={}, id={}",
                    publicId, conversation.getId());
            conversation.setId(IdUtil.getSnowflakeNextId());
            conversationMapper.insert(conversation);
            return conversation;
        }
    }

    /**
     * 会话可用性校验——所有按 conversation_id 查消息/写消息的入口统一调用。
     * <p>
     * 策略：
     * - 主表行存在且非 ACTIVE 或不属于该用户 → 抛业务异常（软删生效）；
     * - 主表行缺失（迁移前的历史会话 / 迁移未执行）→ 记 warn 后放行，
     *   因为消息查询本身还带 user_id 条件，不会造成越权。
     */
    public void assertActive(String publicId, Long userId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        Conversation conversation;
        try {
            conversation = conversationMapper.selectByPublicId(publicId);
        } catch (Exception e) {
            log.warn("[Conversation] 会话状态校验跳过（主表不可用）: publicId={}, reason={}",
                    publicId, e.getMessage());
            return;
        }
        if (conversation == null) {
            log.warn("[Conversation] 会话主表行缺失，按历史数据放行: publicId={}", publicId);
            return;
        }
        if (conversation.getUserId() != null && userId != null
                && !conversation.getUserId().equals(userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        if (!Conversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new BusinessException("会话已删除或已归档");
        }
    }

    // ============ Private ============

    /**
     * 从消息表聚合结果补建主表行，然后回查主表。
     */
    private List<ConversationVO> backfillAndList(Long userId) {
        List<ConversationVO> legacy = messageMapper.selectUserConversations(userId);
        if (legacy.isEmpty()) {
            return legacy;
        }
        int created = 0;
        for (ConversationVO vo : legacy) {
            try {
                Conversation conversation = createIfAbsent(vo.getConversationId(), userId, vo.getTitle());
                int currentCount = conversation.getMessageCount() == null ? 0 : conversation.getMessageCount();
                if (vo.getMessageCount() != null && vo.getMessageCount() > currentCount) {
                    conversationMapper.updateMessageCount(vo.getConversationId(), vo.getMessageCount());
                }
                created++;
            } catch (Exception e) {
                log.warn("[Conversation] 补建会话主表失败: publicId={}, reason={}",
                        vo.getConversationId(), e.getMessage());
            }
        }
        log.warn("[Conversation] 会话主表为空，已从消息表补建 {} 条（建议尽快执行迁移脚本）", created);
        List<ConversationVO> list = conversationMapper.selectPageByUser(userId, MAX_LIST_SIZE, 0);
        return list.isEmpty() ? legacy : list;
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
