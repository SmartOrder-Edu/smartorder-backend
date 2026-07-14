package com.smartorder.edu.agent.tool.impl.space;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.vo.SpaceDashboardVO;
import com.smartorder.edu.learning.service.ISpaceDashboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetLearningSpaceDashboardTool implements ToolHandler {

    private final ISpaceDashboardService spaceDashboardService;
    private final LearningSpaceToolPayloadBuilder payloadBuilder;

    @Override
    public String name() {
        return "getLearningSpaceDashboard";
    }

    @Override
    public String description() {
        return "查询某个学习空间的聚合仪表盘数据，包括当前目标、进度、风险状态、今日任务、逾期任务、学习路径、资源和近 7 天学习记录摘要。"
                + "用于回答某个空间现在学到哪、今天该做什么、是否落后、下一步应该怎么安排。"
                + "调用前必须已知道 spaceId；如果只有空间名称，先调用 resolveLearningSpace。";
    }

    @Override
    public Class<?> requestType() {
        return GetLearningSpaceDashboardRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROGRESS;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GetLearningSpaceDashboardRequest req = (GetLearningSpaceDashboardRequest) request;
        Long userId = context.getUserId();
        Long spaceId = req.getSpaceId();

        log.info("[GetLearningSpaceDashboard] userId={}, spaceId={}", userId, spaceId);

        if (spaceId == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "spaceId 不能为空。请先调用 resolveLearningSpace 根据用户描述匹配学习空间。");
            return error;
        }

        SpaceDashboardVO dashboard = spaceDashboardService.getDashboard(userId, spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("cardType", "learning_space_dashboard");
        result.put("summary", payloadBuilder.buildDashboardSummary(dashboard));
        result.put("dashboard", payloadBuilder.toDashboardCardPayload(dashboard));
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法读取这个学习空间的学习状态";
    }

    @Data
    public static class GetLearningSpaceDashboardRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间ID。必须由 listLearningSpaces 或 resolveLearningSpace 返回，不能猜测")
        private Long spaceId;
    }
}
