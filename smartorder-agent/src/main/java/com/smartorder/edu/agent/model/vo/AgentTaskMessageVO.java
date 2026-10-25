package com.smartorder.edu.agent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务快照中的助手消息投影。
 * <p>
 * {@code content} 是服务端已提交的正文（执行中为增量投影，终态为落库正文），
 * {@code revision} 为快照版本，客户端据此丢弃比本地更旧的快照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务快照中的助手消息")
public class AgentTaskMessageVO {

    @Schema(description = "助手消息 ID")
    private String messageId;

    @Schema(description = "消息角色，固定 assistant")
    private String role;

    @Schema(description = "已提交的正文")
    private String content;

    @Schema(description = "消息状态：STREAMING 或任务终态")
    private String status;

    @Schema(description = "快照版本，单调递增")
    private long revision;

    @Schema(description = "会话内消息序号，尚未落库时为空")
    private Integer sequenceNo;
}
