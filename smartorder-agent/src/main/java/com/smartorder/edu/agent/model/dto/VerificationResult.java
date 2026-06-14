package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 步骤验证结果——Plan-and-Execute 循环中每步执行后的质量检查结果
 * <p>
 * Plan → Execute → Verify → Replan / Continue 闭环的关键数据结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationResult {

    /** 是否通过验证 */
    private boolean passed;

    /** 验证反馈（未通过时说明原因和改进方向） */
    private String feedback;

    /** 建议的后续动作 */
    @Builder.Default
    private SuggestedAction action = SuggestedAction.CONTINUE;

    /** 修正后的任务描述（action=RETRY 时使用） */
    private String revisedDescription;

    /** 建议替换的工具列表（action=RETRY 时使用） */
    private java.util.List<String> revisedTools;

    public enum SuggestedAction {
        /** 通过，继续下一步 */
        CONTINUE,
        /** 用修正参数重试当前任务 */
        RETRY,
        /** 重新规划剩余任务（当前任务需要改方案，非简单重试能解决） */
        REPLAN,
        /** 跳过当前任务（非关键任务失败） */
        SKIP,
        /** 需要向用户追问才能继续 */
        CLARIFY
    }
}
