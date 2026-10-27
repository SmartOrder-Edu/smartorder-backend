package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.service.ConversationMessageService;
import com.smartorder.edu.agent.service.PendingReplyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版“待收尾回复”存储——单机测试使用，语义与 Redis 版一致。
 *
 * <p>这里的 {@code Map} 模拟的是 Redis，不是"省掉 Redis 的本地状态"。
 * 同样保留过期语义，避免测试里出现"生产会过期、测试永不过期"的行为差异。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.sse", name = "event-store", havingValue = "memory")
public class InMemoryPendingReplyStore implements PendingReplyStore {

    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    private final Map<String, Entry> replies = new ConcurrentHashMap<>();

    @Override
    public void save(String traceId, ConversationMessageService.PendingReply reply) {
        if (traceId == null || reply == null) {
            return;
        }
        replies.put(traceId, new Entry(reply, System.currentTimeMillis() + TTL_MILLIS));
    }

    @Override
    public ConversationMessageService.PendingReply load(String traceId) {
        if (traceId == null) {
            return null;
        }
        Entry entry = replies.get(traceId);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            replies.remove(traceId, entry);
            return null;
        }
        return entry.reply;
    }

    @Override
    public void remove(String traceId) {
        if (traceId != null) {
            replies.remove(traceId);
        }
    }

    @Override
    public boolean exists(String traceId) {
        return load(traceId) != null;
    }

    private static final class Entry {

        private final ConversationMessageService.PendingReply reply;

        private final long expireAt;

        private Entry(ConversationMessageService.PendingReply reply, long expireAt) {
            this.reply = reply;
            this.expireAt = expireAt;
        }
    }
}
