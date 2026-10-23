package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.student.algorithm.LearningAlgorithmQueryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询算法学习诊断工具。
 *
 * <p>该工具只读取算法结果，不直接修改掌握度、任务或学习计划。
 * 掌握度与置信度来自确定性 BKT/Beta 链路；LLM 语义分析只参与题目知识点映射和错因归一。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetLearningDiagnosisTool implements ToolHandler {

    private final LearningAlgorithmQueryService queryService;

    @Override
    public String name() {
        return "getLearningDiagnosis";
    }

    @Override
    public String description() {
        return "查询指定学习空间的算法学习诊断，包括薄弱知识点、掌握度、置信度、推荐下一步动作和算法依据。"
                + "当用户询问学习分析、薄弱点、掌握情况、下一步学什么、为什么这样安排时优先调用。"
                + "调用前必须知道 spaceId；如果只有空间名称，先调用 resolveLearningSpace。";
    }

    @Override
    public Class<?> requestType() {
        return GetLearningDiagnosisRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROGRESS;
    }

    @Override
    public ToolCategory.RiskLevel riskLevel() {
        return ToolCategory.RiskLevel.READ;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GetLearningDiagnosisRequest req = (GetLearningDiagnosisRequest) request;
        Long userId = context.getUserId();
        Long spaceId = req.getSpaceId();

        log.info("[GetLearningDiagnosis] userId={}, spaceId={}", userId, spaceId);

        if (spaceId == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "spaceId 不能为空。请先调用 resolveLearningSpace 根据用户描述匹配学习空间。");
            return error;
        }

        Map<String, Object> diagnosis = queryService.getCompactDiagnosis(userId, spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("cardType", "learning_algorithm_diagnosis");
        result.put("diagnosis", diagnosis);
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法读取算法学习诊断";
    }

    @Data
    public static class GetLearningDiagnosisRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间ID。必须由 listLearningSpaces 或 resolveLearningSpace 返回，不能猜测")
        private Long spaceId;
    }
}
