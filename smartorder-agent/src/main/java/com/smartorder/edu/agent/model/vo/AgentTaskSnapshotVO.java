package com.smartorder.edu.agent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务快照（{@code GET /agent/tasks/{taskId}/snapshot}）。
 * <p>
 * 快照是刷新与异常恢复的权威来源：正文与事件游标取自同一行，
 * 因此 {@code snapshotEventId} 之后的增量一定能在事件流中读到，不会出现空洞。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 任务快照")
public class AgentTaskSnapshotVO {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "会话 ID")
    private String conversationId;

    @Schema(description = "任务状态：QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED")
    private String status;

    @Schema(description = "事件流世代")
    private String streamVersion;

    /**
     * 快照版本，单调递增。
     * <p>
     * 用基本类型 {@code long}：全局 Jackson 配置把包装类型 {@link Long} 序列化成字符串，
     * 前端按数值比较新旧快照，字符串比较在 "10" 与 "9" 之间会得出相反结论。
     */
    @Schema(description = "快照版本，单调递增", type = "integer", format = "int64")
    private long snapshotRevision;

    @Schema(description = "已投影的事件游标，续传从此之后读取")
    private String snapshotEventId;

    @Schema(description = "本轮助手消息投影")
    private List<AgentTaskMessageVO> messages;

    @Schema(description = "任务行最后更新时间")
    private LocalDateTime updatedAt;
}
