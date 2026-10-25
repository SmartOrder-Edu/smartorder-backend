package com.smartorder.edu.agent.model.vo;

import com.smartorder.edu.agent.model.entity.AgentChatTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建任务的回执。
 * <p>
 * 立即返回，Agent 在后台继续执行；客户端凭 {@code taskId + streamVersion} 订阅事件流，
 * 凭 {@code messageId} 把流式内容落到对应气泡。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 任务创建回执")
public class AgentTaskReceiptVO {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "会话 ID，请求未指定时由服务端新建")
    private String conversationId;

    @Schema(description = "本轮助手消息 ID")
    private String messageId;

    @Schema(description = "任务状态：QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED")
    private String status;

    @Schema(description = "事件流世代，订阅时需原样带回")
    private String streamVersion;

    /** 任务行 → 回执，幂等重试与首次创建共用同一投影。 */
    public static AgentTaskReceiptVO from(AgentChatTask task) {
        return new AgentTaskReceiptVO(task.getTaskId(), task.getConversationId(), task.getMessageId(),
                task.getStatus(), task.getStreamVersion());
    }
}
