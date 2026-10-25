package com.smartorder.edu.agent.model.vo;

import com.smartorder.edu.agent.model.entity.AgentChatTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话当前任务摘要（{@code GET /agent/tasks/active}）。
 * <p>
 * 用于 F5 恢复：终态任务也要返回，避免"任务刚好完成"时页面永久停留在生成中。
 * 没有任务时返回全空字段对象（而非 null），前端可直接判空 {@code taskId}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话当前任务摘要")
public class AgentTaskSummaryVO {

    @Schema(description = "任务 ID，无任务时为空")
    private String taskId;

    @Schema(description = "会话 ID")
    private String conversationId;

    @Schema(description = "本轮助手消息 ID")
    private String messageId;

    @Schema(description = "任务状态：QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED")
    private String status;

    @Schema(description = "事件流世代")
    private String streamVersion;

    public static AgentTaskSummaryVO from(AgentChatTask task) {
        return new AgentTaskSummaryVO(task.getTaskId(), task.getConversationId(), task.getMessageId(),
                task.getStatus(), task.getStreamVersion());
    }
}
