package com.smartorder.edu.agent.service;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.service.impl.AgentOrchestratorImpl;
import com.smartorder.edu.agent.util.TitleInputExtractor;
import com.smartorder.edu.agent.util.TitleNormalizer;
import com.smartorder.edu.agent.util.UuidUtils;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * - {@code uk_conv_seq(conversation_id, sequence_no)} 是序号唯一性的最终防线；
 *   {@code uk_conv_message_uuid(conversation_id, public_id)} 是消息幂等的最终防线。
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
    private final ConversationDraftService conversationDraftService;

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
        /** assistant 占位行状态；命中幂等且任务行已丢失时用于返回真实状态 */
        private String assistantStatus;
        private int userSequenceNo;
        private int assistantSequenceNo;
        private String title;
        /** 标题来源（AUTO/LLM/USER）：AUTO 说明还没拿到模型标题，后续消息可以再补 */
        private String titleSource;
        /** 本轮是否新建了会话（用于 SSE agent.conversation.created） */
        private boolean created;
        /**
         * true = 命中 conversationId + messageUuid 消息幂等：
         * 本次没有插入任何消息，调用方不得再启动生成，应直接返回已有任务/消息状态。
         */
        private boolean duplicate;
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
     * <p><b>幂等语义</b>：以 {@code conversationId + messageUuid} 为键。
     * 同一键重复到达时不再插入，返回已有 user/assistant 行的 ID 与
     * {@link PendingReply#isDuplicate() duplicate=true}；内容不一致则 409。</p>
     *
     * @param rawConversationId 前端生成的会话 ID（未清洗）；为空时兼容旧客户端，由服务端生成
     * @param messageUuid      前端生成的消息 UUID（写入 conversation_message.public_id）；
     *                         为空时兼容旧客户端，由服务端生成
     * @param draftId          前端新对话草稿 ID；为空时不校验草稿绑定（旧客户端）
     * @return 待收尾的回复（含 conversationId / 占位行ID / 序号）
     */
    @Transactional(rollbackFor = Exception.class)
    public PendingReply openReply(String rawConversationId, String messageUuid, String draftId,
                                  Long userId, String content, List<Long> attachmentFileIds,
                                  String traceId, String taskId) {
        pruneStaleStates();

        // 入参保留原始值（可能为空白串/未清洗），清洗后落到 requestedConversationId；
        // conversationId 是本轮最终使用的值——为空时由服务端生成，因此必须留成可变变量。
        String requestedConversationId = UuidUtils.trimToNull(rawConversationId);
        String conversationId = requestedConversationId;
        String userMessageUuid = UuidUtils.trimToNull(messageUuid);
        String draft = UuidUtils.trimToNull(draftId);
        if (conversationId != null && !UuidUtils.isValid(conversationId)) {
            throw AgentTaskException.badRequest("INVALID_CONVERSATION_UUID",
                    "conversationId 格式非法，必须是 36 位 UUID");
        }
        if (userMessageUuid != null && !UuidUtils.isValid(userMessageUuid)) {
            throw AgentTaskException.badRequest("INVALID_MESSAGE_UUID",
                    "message_uuid 格式非法，必须是 36 位 UUID");
        }
        if (draft != null && !UuidUtils.isValid(draft)) {
            throw AgentTaskException.badRequest("INVALID_DRAFT_ID",
                    "draftId 格式非法，必须是 36 位 UUID");
        }
        // 旧客户端不带 UUID：服务端生成，保持原有能力
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        if (userMessageUuid == null) {
            userMessageUuid = UUID.randomUUID().toString();
        }

        Conversation conversation = conversationMapper.selectByConversationId(conversationId);
        boolean created = false;
        if (conversation == null) {
            // 新对话必须带 draftId；不带 draftId 的“无会话”请求只允许历史数据补建，
            // 避免客户端把错误/过期的 conversationId 悄悄变成一条新会话。
            boolean legacyConversation = requestedConversationId != null
                    && messageMapper.countByConversationId(requestedConversationId, userId) > 0;
            if (requestedConversationId != null && draft == null && !legacyConversation) {
                throw AgentTaskException.conversationNotFound();
            }
            conversation = conversationService.createIfAbsent(conversationId, userId, autoTitle(content));
            created = true;
        } else {
            assertConversationWritable(conversation, userId);
        }

        // 行锁：同一会话的写入串行化。并发首条消息可能在上面 select 之后才提交，
        // 这里必须重新取一次，拿不到再补建。
        Conversation locked = conversationMapper.selectByConversationIdForUpdate(conversationId);
        if (locked == null) {
            conversationService.createIfAbsent(conversationId, userId, autoTitle(content));
            locked = conversationMapper.selectByConversationIdForUpdate(conversationId);
            created = true;
        }
        if (locked == null) {
            throw AgentTaskException.conversationNotFound();
        }
        assertConversationWritable(locked, userId);

        // 草稿绑定必须在归属校验之后：不能让人用别人的 conversationId 抢占 draft 绑定。
        conversationDraftService.bindOrAssert(draft, conversationId, userId);

        // 消息幂等：唯一键 uk_conv_message_uuid 的快速路径（此时已持有会话行锁）
        ConversationMessage existing = messageMapper.selectByMessageUuid(conversationId, userMessageUuid);
        if (existing != null) {
            return duplicateReply(existing, locked, content);
        }

        LocalDateTime now = LocalDateTime.now();
        int userSeq = nextSequenceNo(conversationId);
        int assistantSeq = userSeq + 1;

        ConversationMessage userMessage;
        ConversationMessage assistantMessage;
        try {
            userMessage = newMessage(conversationId, userId, ROLE_USER, content, userSeq,
                    ConversationMessage.STATUS_COMPLETED, null, 1, traceId, taskId, null, null,
                    toFileIdsJson(attachmentFileIds), now, null, userMessageUuid);
            assistantMessage = newMessage(conversationId, userId, ROLE_ASSISTANT, "", assistantSeq,
                    ConversationMessage.STATUS_RUNNING, userMessage.getId(), 1, traceId, taskId,
                    null, null, null, now, null, UUID.randomUUID().toString());
        } catch (DuplicateKeyException duplicate) {
            // 多实例/极端并发：唯一键先于行锁生效。重新按 message_uuid 取已有行，按幂等返回。
            ConversationMessage raced = messageMapper.selectByMessageUuid(conversationId, userMessageUuid);
            if (raced != null) {
                return duplicateReply(raced, locked, content);
            }
            throw duplicate;
        }

        conversationMapper.incrMessageCount(conversationId, 2);
        conversationMapper.touchLastMessage(conversationId, now, normalize(content, PREVIEW_MAX));

        PendingReply state = PendingReply.builder()
                .taskId(taskId)
                .conversationId(conversationId)
                .conversationPk(locked.getId())
                .userMessageId(userMessage.getId())
                .assistantMessageId(assistantMessage.getId())
                .assistantStatus(assistantMessage.getStatus())
                .userSequenceNo(userSeq)
                .assistantSequenceNo(assistantSeq)
                .title(locked.getTitle())
                .titleSource(locked.getTitleSource())
                .created(created)
                .duplicate(false)
                .openedAt(System.currentTimeMillis())
                .build();
        pendingReplies.put(traceId, state);

        log.info("[Message] 开启回复: cid={}, traceId={}, created={}, userSeq={}, msgUuid={}, userMsgId={}, assistantMsgId={}",
                conversationId, traceId, created, userSeq, userMessageUuid,
                userMessage.getId(), assistantMessage.getId());
        return state;
    }

    /**
     * 命中消息幂等：校验同一 message_uuid 的内容/角色，返回已有行。
     */
    private PendingReply duplicateReply(ConversationMessage existing, Conversation locked, String content) {
        if (!ROLE_USER.equals(existing.getRole())) {
            throw AgentTaskException.messageUuidConflict(
                    "message_uuid 已存在且不是用户消息，请重新生成 message_uuid");
        }
        if (!Objects.equals(existing.getContent(), content)) {
            throw AgentTaskException.messageContentConflict(
                    "同一 message_uuid 的消息内容不一致，拒绝写入");
        }
        ConversationMessage assistant = existing.getTaskId() == null ? null
                : messageMapper.selectAssistantByTaskId(existing.getTaskId());
        int userSeq = existing.getSequenceNo() == null ? 0 : existing.getSequenceNo();
        int assistantSeq = assistant != null && assistant.getSequenceNo() != null
                ? assistant.getSequenceNo() : userSeq + 1;
        PendingReply duplicate = PendingReply.builder()
                .taskId(existing.getTaskId())
                .conversationId(existing.getConversationId())
                .conversationPk(locked.getId())
                .userMessageId(existing.getId())
                .assistantMessageId(assistant != null ? assistant.getId() : null)
                .assistantStatus(assistant != null ? assistant.getStatus() : null)
                .userSequenceNo(userSeq)
                .assistantSequenceNo(assistantSeq)
                .title(locked.getTitle())
                .titleSource(locked.getTitleSource())
                .created(false)
                .duplicate(true)
                .openedAt(System.currentTimeMillis())
                .build();
        log.info("[Message] 命中消息幂等: cid={}, messageUuid={}, userMsgId={}, assistantMsgId={}",
                existing.getConversationId(), existing.getPublicId(),
                existing.getId(), assistant != null ? assistant.getId() : null);
        return duplicate;
    }

    /**
     * 会话归属与可写状态校验（403/404，绝不输出“存在但不属于你”的细节）。
     */
    private void assertConversationWritable(Conversation conversation, Long userId) {
        if (conversation.getUserId() != null && userId != null
                && !conversation.getUserId().equals(userId)) {
            throw AgentTaskException.forbiddenConversation();
        }
        if (!Conversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw AgentTaskException.conversationNotFound();
        }
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
                reasoningContent, metadataJson, null, now, null, UUID.randomUUID().toString());

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
                toFileIdsJson(attachmentFileIds), now, null, UUID.randomUUID().toString());

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
                ConversationMessage.STATUS_COMPLETED, null, 0, null, null, null, null, null, now, toolName,
                UUID.randomUUID().toString());
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
                                           LocalDateTime now, String toolName, String publicId) {
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
        message.setPublicId(publicId);
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
        Conversation conversation = conversationMapper.selectByConversationIdForUpdate(conversationId);
        if (conversation == null) {
            conversationService.createIfAbsent(conversationId, userId, null);
            conversation = conversationMapper.selectByConversationIdForUpdate(conversationId);
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
