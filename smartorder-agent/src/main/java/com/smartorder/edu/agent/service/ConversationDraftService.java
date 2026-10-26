package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.mapper.AgentTaskRecordMapper;
import com.smartorder.edu.agent.mapper.ConversationDraftMapper;
import com.smartorder.edu.agent.model.entity.AgentTaskRecord;
import com.smartorder.edu.agent.model.entity.ConversationDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

/**
 * 新对话草稿（draft）与会话 UUID 的绑定服务。
 *
 * <p><b>为什么要 draft</b>：只靠 conversationId 无法区分“同一创建请求的重试”和
 * “用户明确再新建一个对话却复用了旧 ID”。前端每次“新建对话”生成一个 draft_id，
 * 并同时生成 conversationId；两者一对一绑定后：</p>
 * <ul>
 *   <li>同一 draft 重试 → 绑定命中，继续走消息幂等；</li>
 *   <li>新 draft 复用旧 conversationId → {@code CONVERSATION_DRAFT_CONFLICT}；</li>
 *   <li>同一 draft 换 conversationId → {@code DRAFT_CONVERSATION_CONFLICT}；</li>
 *   <li>同一 requestId 换 draft_id → {@code REQUEST_DRAFT_CONFLICT}（绝不静默返回旧会话）。</li>
 * </ul>
 *
 * <p><b>存储</b>：MySQL {@code agent_conversation_draft} 是权威（唯一键最终防线）；
 * Redis 缓存双向映射，命中时省掉 MySQL 往返，缓存丢失时回源 MySQL。</p>
 *
 * <p><b>降级</b>：Redis 不可用时只记日志，不阻断主流程；draft 表尚未迁移时
 * （{@link DataAccessException}）同样降级为“只做消息幂等”，并打出明确告警。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationDraftService {

    /** draft / conversation 绑定缓存：草稿通常只活几分钟，7 天足够覆盖异常重试 */
    private static final Duration DRAFT_TTL = Duration.ofDays(7);
    /** requestId → draftId 缓存：只需覆盖客户端重试窗口 */
    private static final Duration REQUEST_TTL = Duration.ofDays(1);

    private static final String KEY_DRAFT_TO_CONVERSATION = "agent:draft:conv:";
    private static final String KEY_CONVERSATION_TO_DRAFT = "agent:conv:draft:";
    private static final String KEY_REQUEST_TO_DRAFT = "agent:req:draft:";

    private final ConversationDraftMapper draftMapper;

    private final AgentTaskRecordMapper taskMapper;

    private final StringRedisTemplate redis;

    /**
     * 校验并落库 draft_id ↔ conversationId 一对一绑定。
     *
     * <p>必须在 conversation 归属校验之后调用：不能让人用一个别人的
     * conversationId 先把 draft 绑定占住。</p>
     *
     * @throws AgentTaskException 409/403（绑定冲突或归属不符）
     */
    public void bindOrAssert(String draftId, String conversationId, Long userId) {
        if (draftId == null || conversationId == null || userId == null) {
            return;
        }

        String draftKey = KEY_DRAFT_TO_CONVERSATION + draftId;
        String conversationKey = KEY_CONVERSATION_TO_DRAFT + conversationId;
        String cachedConversation = redisGet(draftKey);
        String cachedDraft = redisGet(conversationKey);
        // Redis 值带上 userId：缓存命中也不能绕过归属校验
        String expectedConversation = userId + ":" + conversationId;
        String expectedDraft = userId + ":" + draftId;
        // 双向缓存都命中且一致：最常见的“同一草稿重试”，不必回表
        if (expectedConversation.equals(cachedConversation) && expectedDraft.equals(cachedDraft)) {
            return;
        }

        ConversationDraft byDraft;
        ConversationDraft byConversation;
        try {
            // current read：必须看到并发事务刚提交的绑定，避免旧快照把冲突判成“无冲突”
            byDraft = draftMapper.selectByDraftIdForUpdate(draftId);
            byConversation = draftMapper.selectByConversationIdForUpdate(conversationId);
        } catch (DataAccessException e) {
            if (!isMissingSchema(e)) {
                throw e;
            }
            log.error("[Draft] agent_conversation_draft 表不存在，已降级为只做消息幂等；"
                    + "请执行 db_migration_conversation_id_idempotency.sql: {}", e.getMessage());
            return;
        }

        if (byDraft != null) {
            assertSameOwner(byDraft.getUserId(), userId);
            if (!conversationId.equals(byDraft.getConversationId())) {
                throw AgentTaskException.draftConversationConflict(
                        "草稿 " + draftId + " 已绑定其他会话，不能改绑新的 conversationId");
            }
        }
        if (byConversation != null) {
            assertSameOwner(byConversation.getUserId(), userId);
            if (!draftId.equals(byConversation.getDraftId())) {
                throw AgentTaskException.conversationDraftConflict(
                        "conversationId 已被其他草稿占用，请重新生成 conversationId");
            }
        }

        if (byDraft == null && byConversation == null) {
            ConversationDraft row = new ConversationDraft();
            row.setDraftId(draftId);
            row.setConversationId(conversationId);
            row.setUserId(userId);
            try {
                draftMapper.insert(row);
            } catch (DuplicateKeyException duplicate) {
                // 并发绑定：唯一键冲突后必须用加锁读重新判定，不能直接放行
                ConversationDraft latestByDraft = draftMapper.selectByDraftIdForUpdate(draftId);
                ConversationDraft latestByConversation = draftMapper.selectByConversationIdForUpdate(conversationId);
                assertSameOwner(latestByDraft != null ? latestByDraft.getUserId() : null, userId);
                assertSameOwner(latestByConversation != null ? latestByConversation.getUserId() : null, userId);
                if (latestByDraft != null && !conversationId.equals(latestByDraft.getConversationId())) {
                    throw AgentTaskException.draftConversationConflict(
                            "草稿 " + draftId + " 已绑定其他会话");
                }
                if (latestByConversation != null && !draftId.equals(latestByConversation.getDraftId())) {
                    throw AgentTaskException.conversationDraftConflict(
                            "conversationId 已被其他草稿占用，请重新生成 conversationId");
                }
                if (latestByDraft == null && latestByConversation == null) {
                    // 唯一键冲突却读不到行：说明对方事务还在提交窗口内，交给客户端重试
                    throw AgentTaskException.draftBindingConflict(
                            "草稿绑定并发冲突，请重试一次");
                }
                log.info("[Draft] 并发绑定命中同一映射，按幂等放行: draftId={}, conversationId={}",
                        draftId, conversationId);
            }
        }

        redisSet(draftKey, userId + ":" + conversationId, DRAFT_TTL);
        redisSet(conversationKey, userId + ":" + draftId, DRAFT_TTL);
        log.info("[Draft] 绑定校验通过: draftId={}, conversationId={}, userId={}",
                draftId, conversationId, userId);
    }

    /**
     * 同一 requestId 只能对应同一个 draftId。
     *
     * <p>Redis 先判（快），MySQL {@code agent_chat_task} 兜底（权威）。发现不一致时
     * 直接 409，而不是把旧任务/旧会话返回给客户端。</p>
     */
    public void assertRequestDraft(Long userId, String requestId, String draftId) {
        if (userId == null || requestId == null || draftId == null) {
            return;
        }
        String key = requestDraftKey(userId, requestId);
        String cached = redisGet(key);
        if (cached != null && !cached.equals(draftId)) {
            throw AgentTaskException.requestDraftConflict(
                    "同一个 requestId 不允许更换 draftId，请为新草稿生成新的 requestId");
        }
        AgentTaskRecord record = taskMapper.selectByTaskRequest(userId, requestId);
        if (record != null) {
            if (record.getDraftId() != null && !record.getDraftId().equals(draftId)) {
                throw AgentTaskException.requestDraftConflict(
                        "同一个 requestId 已绑定其他草稿，拒绝复用");
            }
            redisSet(key, record.getDraftId() != null ? record.getDraftId() : draftId, REQUEST_TTL);
            return;
        }
        redisSet(key, draftId, REQUEST_TTL);
    }

    /**
     * 任务预留成功后回写 requestId → draftId 缓存。
     */
    public void rememberRequestDraft(Long userId, String requestId, String draftId) {
        if (userId == null || requestId == null || draftId == null) {
            return;
        }
        redisSet(requestDraftKey(userId, requestId), draftId, REQUEST_TTL);
    }

    private String requestDraftKey(Long userId, String requestId) {
        return KEY_REQUEST_TO_DRAFT + userId + ":" + requestId;
    }

    private void assertSameOwner(Long ownerUserId, Long currentUserId) {
        if (ownerUserId != null && currentUserId != null && !Objects.equals(ownerUserId, currentUserId)) {
            throw AgentTaskException.forbiddenConversation();
        }
    }

    /**
     * 只对“表/列还没迁移”这一种数据库错误降级；其他数据库异常必须继续抛，不能被吞掉。
     */
    private boolean isMissingSchema(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if (lower.contains("doesn't exist")
                    || lower.contains("does not exist")
                    || lower.contains("unknown table")
                    || lower.contains("unknown column")) {
                return true;
            }
        }
        return false;
    }

    private String redisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[Draft] Redis 读取失败，回源 MySQL: key={}, reason={}", key, e.getMessage());
            return null;
        }
    }

    private void redisSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("[Draft] Redis 写入失败（不影响 MySQL 权威绑定）: key={}, reason={}",
                    key, e.getMessage());
        }
    }
}
