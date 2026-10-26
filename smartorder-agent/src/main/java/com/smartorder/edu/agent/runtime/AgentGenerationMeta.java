package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.model.enums.AgentGenerationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成任务的元数据（不含事件正文）。
 *
 * <p>存 Redis Hash，用于“进程不再持有该生成”之后的查询/续传：状态、最后序号、
 * 归属用户、会话关联、错误原因。事件正文另存一个 List。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentGenerationMeta {

    private String generationId;

    /** 会话 public_id；新会话在 openTurn 之后才能确定，因此允许为空 */
    private String conversationId;

    /** 归属用户——订阅/查询/取消都必须校验，不能把 generationId 当授权凭证 */
    private Long userId;

    private String traceId;

    /** 客户端幂等键，同一个用户重复提交时复用同一次生成 */
    private String clientRequestId;

    private String status;

    private long createdAt;

    private long updatedAt;

    /** 已产生事件的最大 seq */
    private long lastSeq;

    /** 用户消息行 ID */
    private Long userMessageId;

    private Long assistantMessageId;

    private Integer assistantSequenceNo;

    /** 会话兜底标题 */
    private String title;

    private String errorMessage;

    /**
     * 事件是否完整可回放。
     * <p>异步镜像写 Redis 失败时会置 false：此时只能靠本机内存回放，
     * 跨实例/重启后的续传会返回 CURSOR_EXPIRED，而不是静默跳到最新事件。</p>
     */
    @Builder.Default
    private boolean replayable = true;

    public AgentGenerationStatus statusEnum() {
        AgentGenerationStatus parsed = AgentGenerationStatus.fromName(status);
        return parsed != null ? parsed : AgentGenerationStatus.RUNNING;
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        put(map, "generationId", generationId);
        put(map, "conversationId", conversationId);
        put(map, "userId", userId);
        put(map, "traceId", traceId);
        put(map, "clientRequestId", clientRequestId);
        put(map, "status", status);
        put(map, "createdAt", createdAt);
        put(map, "updatedAt", updatedAt);
        put(map, "lastSeq", lastSeq);
        put(map, "userMessageId", userMessageId);
        put(map, "assistantMessageId", assistantMessageId);
        put(map, "assistantSequenceNo", assistantSequenceNo);
        put(map, "title", title);
        put(map, "errorMessage", errorMessage);
        put(map, "replayable", replayable ? "1" : "0");
        return map;
    }

    public static AgentGenerationMeta fromMap(Map<Object, Object> raw) {
        if (raw == null || raw.isEmpty() || raw.get("generationId") == null) {
            return null;
        }
        AgentGenerationMeta meta = new AgentGenerationMeta();
        meta.setGenerationId(str(raw.get("generationId")));
        meta.setConversationId(str(raw.get("conversationId")));
        meta.setUserId(longOrNull(raw.get("userId")));
        meta.setTraceId(str(raw.get("traceId")));
        meta.setClientRequestId(str(raw.get("clientRequestId")));
        meta.setStatus(str(raw.get("status")));
        meta.setCreatedAt(longOrZero(raw.get("createdAt")));
        meta.setUpdatedAt(longOrZero(raw.get("updatedAt")));
        meta.setLastSeq(longOrZero(raw.get("lastSeq")));
        meta.setUserMessageId(longOrNull(raw.get("userMessageId")));
        meta.setAssistantMessageId(longOrNull(raw.get("assistantMessageId")));
        meta.setAssistantSequenceNo(intOrNull(raw.get("assistantSequenceNo")));
        meta.setTitle(str(raw.get("title")));
        meta.setErrorMessage(str(raw.get("errorMessage")));
        meta.setReplayable(!"0".equals(str(raw.get("replayable"))));
        return meta;
    }

    private static void put(Map<String, String> map, String key, Object value) {
        if (value != null) {
            map.put(key, String.valueOf(value));
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longOrZero(Object value) {
        Long parsed = longOrNull(value);
        return parsed == null ? 0L : parsed;
    }
}
