package com.smartorder.edu.agent.service;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.service.impl.AgentOrchestratorImpl;
import com.smartorder.edu.agent.util.TitleInputExtractor;
import com.smartorder.edu.agent.util.TitleNormalizer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话消息服务——聊天消息落库与回复收尾。
 * <p>
 * 为什么必须是独立 Bean：{@code AgentOrchestratorImpl} 内部方法自调用时
 * Spring AOP 事务不生效，所以 openReply / completeReply / failReply 各自
 * {@code @Transactional}，由外部 Bean 调用。
 * <p>
 * 并发模型：
 * - 同一会话的写入先 {@code SELECT ... FOR UPDATE} 锁住 conversation 行，
 *   序号分配天然串行；不同会话互不影响。
 * - 锁持有时间 = 事务 A 的 5 条 SQL（毫秒级），不会卡住 SSE。
 * - {@code uk_conv_seq(conversation_id, sequence_no)} 是最终防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private static final int PREVIEW_MAX = 200;
    private static final int ERROR_MAX = 500;

    /** 未收尾的回复状态超过该时长视为泄漏，openReply 时顺手清理 */
    private static final long STATE_TTL_MS = 30 * 60 * 1000L;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationService conversationService;

    /** traceId → 本轮待收尾的回复；complete/fail/cancel 后移除 */
    private final Map<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();

    /**
     * 一轮问答中待收尾的回复（用户消息 + assistant 占位行）
     */
    @Data
    @Builder
    public static class PendingReply {
        /** 本轮对话任务 ID；user / assistant 两行写入同一个 task_id */
        private String taskId;
        private String conversationId;
        private Long conversationPk;
        private Long userMessageId;
        private Long assistantMessageId;
        private int userSequenceNo;
        private int assistantSequenceNo;
        private String title;
        /** 标题来源（AUTO/LLM/USER）：AUTO 说明还没拿到模型标题，后续消息可以再补 */
        private String titleSource;
        /** 本轮是否新建了会话（用于 SSE agent.conversation.created） */
        private boolean created;
        private long openedAt;
    }

    // ============================================================
    // 事务 A：开启回复（插入用户消息 + assistant 占位行）
    // ============================================================

    /**
     * 开启一次回答：插入用户消息 + assistant 的 RUNNING 占位行，返回待收尾的回复。
     * <p>
     * 用户刷新页面时，正在生成的回答在库里是可见的（RUNNING），
     * 服务端崩溃后也有"僵尸行"可清理——这是与旧实现最大的区别。
     *
     * @param conversationId 前端传入的会话ID，为空则新建
     * @return 待收尾的回复（含 conversationId / 占位行ID / 序号）
     */
    @Transactional(rollbackFor = Exception.class)
    public PendingReply openReply(String conversationId, Long userId, String content,
                              List<Long> attachmentFileIds, String traceId, String taskId) {
        pruneStaleStates();

        String publicId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        Conversation conversation = conversationMapper.selectByPublicId(publicId);
        boolean created = false;
        if (conversation == null) {
            conversation = conversationService.createIfAbsent(publicId, userId, autoTitle(content));
            created = true;
        } else {
            if (conversation.getUserId() != null && userId != null
                    && !conversation.getUserId().equals(userId)) {
                throw new IllegalStateException("会话不存在或无权访问");
            }
            if (!Conversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
                throw new IllegalStateException("会话已删除或已归档");
            }
        }

        // 行锁：同一会话的写入串行化
        Conversation locked = conversationMapper.selectByPublicIdForUpdate(publicId);
        if (locked == null) {
            conversationService.createIfAbsent(publicId, userId, autoTitle(content));
            locked = conversationMapper.selectByPublicIdForUpdate(publicId);
        }

        LocalDateTime now = LocalDateTime.now();
        int userSeq = nextSequenceNo(publicId);
        int assistantSeq = userSeq + 1;

        ConversationMessage userMessage = newMessage(publicId, userId, ROLE_USER, content, userSeq,
                ConversationMessage.STATUS_COMPLETED, null, 1, traceId, taskId, null, null,
                toFileIdsJson(attachmentFileIds), now, null);
        ConversationMessage assistantMessage = newMessage(publicId, userId, ROLE_ASSISTANT, "", assistantSeq,
                ConversationMessage.STATUS_RUNNING, userMessage.getId(), 1, traceId, taskId, null, null, null, now, null);

        conversationMapper.incrMessageCount(publicId, 2);
        conversationMapper.touchLastMessage(publicId, now, normalize(content, PREVIEW_MAX));

        PendingReply state = PendingReply.builder()
                .taskId(taskId)
                .conversationId(publicId)
                .conversationPk(locked != null ? locked.getId() : null)
                .userMessageId(userMessage.getId())
                .assistantMessageId(assistantMessage.getId())
                .userSequenceNo(userSeq)
                .assistantSequenceNo(assistantSeq)
                .title(locked != null ? locked.getTitle() : autoTitle(content))
                .titleSource(locked != null ? locked.getTitleSource() : Conversation.TITLE_SOURCE_AUTO)
                .created(created)
                .openedAt(System.currentTimeMillis())
                .build();
        pendingReplies.put(traceId, state);

        log.info("[Message] 开启回复: cid={}, traceId={}, created={}, userSeq={}, assistantMsgId={}",
                publicId, traceId, created, userSeq, assistantMessage.getId());
        return state;
    }

    // ============================================================
    // 事务 B：收尾
    // ============================================================

    /**
     * 收尾占位行（成功路径）。
     *
     * @return true=命中了本轮占位行并已 UPDATE；false=未命中/已收尾过，调用方应退化为 INSERT
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeReply(String traceId, String content, String reasoningContent, String metadataJson) {
        PendingReply state = traceId == null ? null : pendingReplies.get(traceId);
        if (state == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = messageMapper.markCompleted(state.getAssistantMessageId(), content,
                reasoningContent, metadataJson, now);
        if (updated == 0) {
            // 占位行已被收尾（同一轮第二次调用）→ 交给调用方 INSERT 新气泡
            pendingReplies.remove(traceId);
            return false;
        }
        conversationMapper.touchLastMessage(state.getConversationId(), now, normalize(content, PREVIEW_MAX));
        pendingReplies.remove(traceId);
        log.info("[Message] 完成回复: cid={}, traceId={}, msgId={}", state.getConversationId(),
                traceId, state.getAssistantMessageId());
        return true;
    }

    /**
     * 失败收尾（引擎异常 / 超时 / 预算耗尽）。
     * <p>
     * 占位行置 FAILED + error_message，保留已生成的部分内容，前端可展示"生成失败，点击重试"。
     *
     * @return true=已处理本轮占位行
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean failReply(String traceId, String errorMessage, String partialContent) {
        PendingReply state = traceId == null ? null : pendingReplies.get(traceId);
        if (state == null) {
            return false;
        }
        int updated = messageMapper.markFailed(state.getAssistantMessageId(), partialContent,
                truncate(errorMessage, ERROR_MAX), LocalDateTime.now());
        pendingReplies.remove(traceId);
        log.warn("[Message] 失败回复: cid={}, traceId={}, msgId={}, affected={}, reason={}",
                state.getConversationId(), traceId, state.getAssistantMessageId(), updated,
                truncate(errorMessage, 120));
        return updated > 0;
    }

    /**
     * 用户中止（"停止生成"）：保留已生成内容，状态置 CANCELLED。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelReply(String traceId, String partialContent) {
        PendingReply state = traceId == null ? null : pendingReplies.get(traceId);
        if (state == null) {
            return false;
        }
        int updated = messageMapper.markCancelled(state.getAssistantMessageId(), partialContent,
                LocalDateTime.now());
        pendingReplies.remove(traceId);
        log.info("[Message] 用户中止: cid={}, traceId={}, msgId={}, affected={}",
                state.getConversationId(), traceId, state.getAssistantMessageId(), updated);
        return updated > 0;
    }

    // ============================================================
    // 追加消息（未命中占位行时的退化路径 / 一轮多气泡 / 工具消息）
    // ============================================================

    /**
     * 追加一条 assistant 消息（一轮内第 2 个及以后的气泡，或非占位路径）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendAssistantMessage(String conversationId, Long userId, String content,
                                       String reasoningContent, String traceId, String metadataJson) {
        Conversation conversation = lockActiveConversation(conversationId, userId);
        if (conversation == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int seq = nextSequenceNo(conversationId);
        Long parentId = messageMapper.selectLastUserMessageIdForUpdate(conversationId);
        newMessage(conversationId, userId, ROLE_ASSISTANT, content, seq,
                ConversationMessage.STATUS_COMPLETED, parentId, 1, traceId, null,
                reasoningContent, metadataJson, null, now, null);

        conversationMapper.incrMessageCount(conversationId, 1);
        conversationMapper.touchLastMessage(conversationId, now, normalize(content, PREVIEW_MAX));
    }

    /**
     * 追加一条用户消息（如计划澄清回答、计划确认文本）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendUserMessage(String conversationId, Long userId, String content,
                                  List<Long> attachmentFileIds) {
        Conversation conversation = lockActiveConversation(conversationId, userId);
        if (conversation == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int seq = nextSequenceNo(conversationId);
        newMessage(conversationId, userId, ROLE_USER, content, seq,
                ConversationMessage.STATUS_COMPLETED, null, 1, null, null, null, null,
                toFileIdsJson(attachmentFileIds), now, null);

        conversationMapper.incrMessageCount(conversationId, 1);
        conversationMapper.touchLastMessage(conversationId, now, normalize(content, PREVIEW_MAX));
    }

    /**
     * 追加工具消息：占序号但 is_visible=0，不计入 message_count，也不进模型上下文。
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendToolMessage(String conversationId, Long userId, String toolName, String content) {
        Conversation conversation = lockActiveConversation(conversationId, userId);
        if (conversation == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int seq = nextSequenceNo(conversationId);
        newMessage(conversationId, userId, ROLE_TOOL, content, seq,
                ConversationMessage.STATUS_COMPLETED, null, 0, null, null, null, null, null, now, toolName);
    }

    public boolean hasPendingReply(String traceId) {
        return traceId != null && pendingReplies.containsKey(traceId);
    }

    // ============================================================
    // Private
    // ============================================================

    private ConversationMessage newMessage(String conversationId, Long userId, String role, String content,
                                           int sequenceNo, String status, Long parentMessageId, int visible,
                                           String traceId, String taskId, String reasoningContent,
                                           String metadataJson, String attachmentFileIds,
                                           LocalDateTime now, String toolName) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setToolName(toolName);
        message.setSequenceNo(sequenceNo);
        message.setStatus(status);
        message.setParentMessageId(parentMessageId);
        message.setAttemptNo(1);
        message.setIsVisible(visible);
        message.setPublicId(UUID.randomUUID().toString());
        message.setTraceId(traceId);
        message.setTaskId(taskId);
        message.setReasoningContent(reasoningContent);
        message.setMetadataJson(metadataJson);
        message.setAttachmentFileIds(attachmentFileIds);
        message.setIsCompressed(0);
        message.setIsDeleted(0);
        message.setStartedAt(now);
        if (!ConversationMessage.STATUS_RUNNING.equals(status)) {
            message.setCompletedAt(now);
        }
        messageMapper.insert(message);
        return message;
    }

    /**
     * 序号分配：必须走加锁读（current read）。
     * 事务开头已有普通 SELECT 建立了快照，普通 SELECT MAX 会读到旧值。
     */
    private int nextSequenceNo(String conversationId) {
        Integer tail = messageMapper.selectTailSequenceNoForUpdate(conversationId);
        return tail == null ? 1 : tail + 1;
    }

    /**
     * 锁住会话行并校验可写。主表行缺失时就地补建（迁移遗漏兜底）。
     */
    private Conversation lockActiveConversation(String conversationId, Long userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Conversation conversation = conversationMapper.selectByPublicIdForUpdate(conversationId);
        if (conversation == null) {
            conversationService.createIfAbsent(conversationId, userId, null);
            conversation = conversationMapper.selectByPublicIdForUpdate(conversationId);
            if (conversation == null) {
                log.warn("[Message] 会话主表行补建失败，丢弃消息写入: cid={}", conversationId);
                return null;
            }
        }
        if (!Conversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            log.warn("[Message] 会话不可写（{}），丢弃消息写入: cid={}", conversation.getStatus(), conversationId);
            return null;
        }
        return conversation;
    }

    private String toFileIdsJson(List<Long> attachmentFileIds) {
        return (attachmentFileIds != null && !attachmentFileIds.isEmpty())
                ? JSON.toJSONString(attachmentFileIds) : null;
    }

    /**
     * 标题/预览统一做空白折叠，避免多行消息把列表撑乱。
     */
    private String normalize(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max);
    }

    /**
     * AUTO 兜底标题：剥掉注入块（{@code [资源上下文]}）后压成标签，
     * 而不是把原始消息截前 30 字——后者会把 Markdown 和系统提示一起显示在列表里。
     * <p>模型标题由 {@code ConversationTitleService} 异步补上，这里只保证"第一眼不难看"。</p>
     */
    private String autoTitle(String content) {
        return TitleNormalizer.fallbackTitle(TitleInputExtractor.extract(content));
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private void pruneStaleStates() {
        if (pendingReplies.size() < 512) {
            return;
        }
        long threshold = System.currentTimeMillis() - STATE_TTL_MS;
        pendingReplies.entrySet().removeIf(e -> e.getValue().getOpenedAt() < threshold);
    }
}
