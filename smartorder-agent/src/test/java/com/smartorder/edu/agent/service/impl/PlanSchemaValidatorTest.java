package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlanSchemaValidator 单元测试，重点覆盖 CONFIRMATION 门业务语义校验。
 */
class PlanSchemaValidatorTest {

    private PlanSchemaValidator validator;

    @BeforeEach
    void setUp() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.contains("createLearningPlan")).thenReturn(true);
        when(toolRegistry.contains("getStudentProfile")).thenReturn(true);
        when(toolRegistry.contains("generateResource")).thenReturn(true);
        validator = new PlanSchemaValidator(toolRegistry);
    }

    private String plan(String... steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"userGoal\":\"测试目标\",\"steps\":[");
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(steps[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 用例 C（回归）：createLearningPlan 单步，无 CONFIRMATION 门 → 通过 */
    @Test
    void validateAcceptsGoalPlanningPlanWithoutConfirmationGate() {
        String step1 = "{\"stepId\":\"step-1\",\"description\":\"获取学生画像\","
                + "\"requiredTools\":[\"getStudentProfile\"],\"dependsOn\":[],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"READ\",\"expectedOutput\":\"画像\"}";
        String step2 = "{\"stepId\":\"step-2\",\"description\":\"生成学习计划草稿\","
                + "\"requiredTools\":[\"createLearningPlan\"],\"dependsOn\":[\"step-1\"],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"WRITE_DRAFT\",\"expectedOutput\":\"草稿\"}";

        ExecutionPlan result = validator.validateAndParseExecutionPlan(plan(step1, step2));

        assertThat(result.getSteps()).hasSize(2);
        assertThat(result.getSteps().get(1).getGate()).isEqualTo("NORMAL");
    }

    /** 用例 A：createLearningPlan + 尾部 CONFIRMATION 门（无下游依赖）→ 抛异常 */
    @Test
    void validateRejectsTrailingConfirmationGateWithoutDependents() {
        String step1 = "{\"stepId\":\"step-1\",\"description\":\"获取学生画像\","
                + "\"requiredTools\":[\"getStudentProfile\"],\"dependsOn\":[],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"READ\",\"expectedOutput\":\"画像\"}";
        String step2 = "{\"stepId\":\"step-2\",\"description\":\"生成学习计划草稿\","
                + "\"requiredTools\":[\"createLearningPlan\"],\"dependsOn\":[\"step-1\"],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"WRITE_DRAFT\",\"expectedOutput\":\"草稿\"}";
        String step3 = "{\"stepId\":\"step-3\",\"description\":\"展示草稿等待确认\","
                + "\"requiredTools\":[],\"dependsOn\":[\"step-2\"],"
                + "\"gate\":\"CONFIRMATION\",\"riskLevel\":\"WRITE_CONFIRMED\",\"expectedOutput\":\"确认结果\"}";

        assertThatThrownBy(() -> validator.validateAndParseExecutionPlan(plan(step1, step2, step3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step-3")
                .hasMessageContaining("没有后续依赖步骤");
    }

    /** 用例 B：CONFIRMATION 门守护后续真实工具步骤（有下游依赖）→ 通过 */
    @Test
    void validateAcceptsConfirmationGateGuardingSubsequentToolStep() {
        String step1 = "{\"stepId\":\"step-1\",\"description\":\"获取学生画像\","
                + "\"requiredTools\":[\"getStudentProfile\"],\"dependsOn\":[],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"READ\",\"expectedOutput\":\"画像\"}";
        String step2 = "{\"stepId\":\"step-2\",\"description\":\"等待用户确认后生成资料\","
                + "\"requiredTools\":[],\"dependsOn\":[\"step-1\"],"
                + "\"gate\":\"CONFIRMATION\",\"riskLevel\":\"WRITE_CONFIRMED\",\"expectedOutput\":\"确认结果\"}";
        String step3 = "{\"stepId\":\"step-3\",\"description\":\"生成学习资料\","
                + "\"requiredTools\":[\"generateResource\"],\"dependsOn\":[\"step-2\"],"
                + "\"gate\":\"NORMAL\",\"riskLevel\":\"WRITE_CONFIRMED\",\"expectedOutput\":\"资料\"}";

        ExecutionPlan result = validator.validateAndParseExecutionPlan(plan(step1, step2, step3));

        assertThat(result.getSteps()).hasSize(3);
        assertThat(result.getSteps().get(1).getGate()).isEqualTo("CONFIRMATION");
    }
}
