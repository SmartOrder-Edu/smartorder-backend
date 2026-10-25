package com.smartorder.edu.agent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅任务事件流（{@code GET /agent/tasks/{taskId}/events}）的查询参数。
 * <p>
 * 所有字段都来自查询串，供浏览器 {@code EventSource} 或 fetch 订阅使用；
 * {@code Last-Event-ID} 走请求头，由 Controller 单独传入，不在此绑定。
 */
@Data
@NoArgsConstructor
@Schema(description = "订阅 Agent 任务事件流查询参数")
public class AgentTaskSubscribeQuery {

    /** 起始游标；缺省从 Stream 起点重放，保证断线后不丢事件。 */
    @Schema(description = "起始事件游标，格式 毫秒-序号，缺省 0-0")
    private String after = "0-0";

    /**
     * 客户端持有的 Stream 世代。
     * <p>
     * 与任务当前世代不一致说明服务端重建过事件流，必须让客户端重新拉快照对齐，
     * 不能继续在旧游标上追加。
     */
    @NotBlank(message = "streamVersion 不能为空")
    @Schema(description = "Stream 世代标识，来自创建任务或快照响应",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String streamVersion;

    /** 断点请求携带的消息 ID，必须与任务绑定的助手消息一致。 */
    @Schema(description = "助手消息 ID，传入时必须与任务匹配")
    private String messageId;
}
