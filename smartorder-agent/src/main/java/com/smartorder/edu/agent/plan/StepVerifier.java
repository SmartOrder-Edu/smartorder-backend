package com.smartorder.edu.agent.plan;

import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.dto.VerificationResult;

import java.util.Map;

/**
 * 步骤验证器——Plan-and-Execute 循环中的质量守门人。
 * <p>
 * 每个 ExecutionStep 执行后验证结果质量，输出通过/失败和建议动作。
 */
public interface StepVerifier {

    /**
     * 验证执行步骤的结果
     *
     * @param step            当前执行的步骤
     * @param result          执行结果文本（ReAct 最终回答）
     * @param previousOutputs 前置步骤的输出（上下文）
     * @return 验证结果，包含是否通过、失败反馈、建议动作
     */
    VerificationResult verify(ExecutionStep step, String result, Map<String, String> previousOutputs);
}
