package com.smartorder.edu.agent.tool;

import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 工具调用上下文——不要在工具里直接依赖 SecurityContext.getCurrentUserId()
 * 因为工具可能运行在异步线程、子智能体线程、定时任务里
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeContext {

    private Long userId;

    private String conversationId;

    private String traceId;

    private AgentRunMode mode;

    private AgentRuntimeContext runtimeContext;

    private SseEmitter emitter;

    @Builder.Default
    private String agentName = "MainAgent";

    private Map<String, Object> attributes;
}
