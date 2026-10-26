package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.constant.AgentEventNames;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务事件日志中的一条事件。
 *
 * <p>{@code seq} 是本次任务内严格递增的整数游标（从 1 开始），同时作为 SSE 的
 * {@code id:} 字段下发。客户端重连时用“已成功应用的 seq”作为 after 游标，
 * 服务端只补发 {@code seq > after} 的事件，因此不会重复也不会跳号。</p>
 *
 * <p>{@code data} 是完整的事件 JSON（与 SSE 的 data 行逐字一致），
 * 回放时直接原样写出，保证“实时看到的事件”和“回放拿到的事件”完全相同。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskEvent {

    /** 任务内序号，从 1 开始严格递增 */
    private long seq;

    /** SSE 事件名，如 agent.text.delta / done */
    private String eventName;

    /** SSE data 行（完整事件 JSON） */
    private String data;

    /** 事件产生时间（毫秒） */
    private long timestamp;

    /** 是否为终态事件（done） */
    public boolean isTerminal() {
        return AgentEventNames.DONE.equals(eventName);
    }
}
