package com.smartorder.edu.agent.subagent;

import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 子智能体接口——统一子智能体契约
 */
public interface SubAgent {

    /** 子智能体名称 */
    String name();

    /** 子智能体描述 */
    String description();

    /** 执行子任务 */
    SubAgentResult run(SubAgentTask task, AgentRuntimeContext parentContext, SseEmitter emitter);
}
