package com.smartorder.edu.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 可恢复 Agent 对话任务（一次用户提交 = 一行）。
 * <p>
 * 该表同时承担三个职责：
 * 1) 幂等仲裁：{@code (tenant_id, user_id, request_id)} 唯一键阻止重复提交重跑 Agent；
 * 2) 内容投影：{@code snapshot_content} 与 {@code snapshot_event_id} 在同一 SQL 内推进，
 *    保证数据库快照永不超前于已发布事件；
 * 3) 恢复锚点：{@code stream_version} 标识 Redis Stream 世代，用于识别跨世代重连。
 * <p>
 * 任务 ID 由应用生成（{@link IdType#INPUT}），不使用数据库自增。
 */
@Data
@NoArgsConstructor
@TableName("agent_chat_task")
public class AgentChatTask {

    /** 已入队，尚未开始执行 */
    public static final String STATUS_QUEUED = "QUEUED";
    /** Agent 正在执行 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 正常结束 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 执行失败或调度失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 用户中止 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 快照状态：任务未终结但客户端需要展示"仍在生成" */
    public static final String SNAPSHOT_STATUS_STREAMING = "STREAMING";

    /** 任务及本轮 trace ID */
    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    /** 本轮助手消息对外 ID，在创建任务时预分配 */
    private String messageId;

    /** 会话 public_id */
    private String conversationId;

    /** 任务所有者 */
    private Long userId;

    /** 身份令牌存在租户时的租户标识 */
    private String tenantId;

    /** 客户端稳定幂等键 */
    private String requestId;

    /** 请求内容摘要（SHA-256），用于识别同 requestId 不同内容的非法复用 */
    private String requestHash;

    /** QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;

    /** Redis Stream 世代 */
    private String streamVersion;

    /** 首次写入前已持久标记，防止丢流后重建 */
    private Boolean streamStarted;

    /** 成功写入 Redis 的事件数 */
    private Long eventCount;

    /** 消息投影已包含的事件游标 */
    private String snapshotEventId;

    /** 单调快照版本 */
    private Long snapshotRevision;

    /** 执行中已提交的回答增量投影 */
    private String snapshotContent;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    /** 终态统一判断，避免 SSE 读取线程在任务完成后无限阻塞。 */
    public static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    /** 事件数量缺失按 0 处理，避免自动拆箱空指针。 */
    public long eventCountOrZero() {
        return eventCount == null ? 0L : eventCount;
    }

    /** 快照版本缺失按 0 处理；对外必须以 JSON 数字下发，否则前端版本比较会退化成字符串比较。 */
    public long snapshotRevisionOrZero() {
        return snapshotRevision == null ? 0L : snapshotRevision;
    }
}
