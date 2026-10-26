package com.smartorder.edu.agent.tool.impl.space;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.domain.vo.SpaceDashboardVO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.ISpaceDashboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListLearningSpacesTool implements ToolHandler {

    private static final int MAX_VISIBLE_SPACES = 6;

    private final ILearningSpaceService learningSpaceService;
    private final ISpaceDashboardService spaceDashboardService;
    private final LearningSpaceToolPayloadBuilder payloadBuilder;

    @Override
    public String name() {
        return "listLearningSpaces";
    }

    @Override
    public String description() {
        return "查询当前用户的学习空间列表，可按状态、类型、关键词和风险状态筛选。"
                + "用于了解用户有哪些学习目标、选择要进入的学习空间、跨空间安排今日学习。"
                + "如果用户提到'我的学习空间'、'有哪些课程/目标'、'今天应该先看哪个空间'，优先调用此工具。";
    }

    @Override
    public Class<?> requestType() {
        return ListLearningSpacesRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROGRESS;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ListLearningSpacesRequest req = (ListLearningSpacesRequest) request;
        Long userId = context.getUserId();
        String status = blankToNull(req.getStatus());
        String type = blankToNull(req.getType());
        String keyword = blankToNull(req.getKeyword());
        String riskStatus = blankToNull(req.getRiskStatus());

        log.info("[ListLearningSpaces] userId={}, status={}, type={}, keyword={}, risk={}",
                userId, status, type, keyword, riskStatus);

        List<LearningSpaceVO> spaces = learningSpaceService.listSpaces(userId, status).stream()
                .filter(space -> type == null || type.equals(space.getType()))
                .filter(space -> matchesKeyword(space, keyword))
                .toList();

        List<Map<String, Object>> matchedSummaries = spaces.stream()
                .map(space -> toSummary(userId, space))
                .filter(summary -> riskStatus == null || riskStatus.equals(summary.get("riskStatus")))
                .toList();
        List<Map<String, Object>> visibleSummaries = matchedSummaries.stream()
                .limit(MAX_VISIBLE_SPACES)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("cardType", "learning_space_list");
        result.put("summary", "共找到 " + matchedSummaries.size() + " 个学习空间"
                + (riskStatus != null ? "，风险状态为 " + riskStatus : ""));
        result.put("total", matchedSummaries.size());
        result.put("visibleCount", visibleSummaries.size());
        result.put("spaces", visibleSummaries);
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法读取你的学习空间列表";
    }

    private Map<String, Object> toSummary(Long userId, LearningSpaceVO space) {
        try {
            SpaceDashboardVO dashboard = spaceDashboardService.getDashboard(userId, space.getId());
            return payloadBuilder.toSpaceSummary(space, dashboard);
        } catch (Exception e) {
            log.warn("[ListLearningSpaces] dashboard enrich failed, spaceId={}: {}",
                    space.getId(), e.getMessage());
            return payloadBuilder.toSpaceSummary(space, null);
        }
    }

    private boolean matchesKeyword(LearningSpaceVO space, String keyword) {
        if (keyword == null) return true;
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return contains(space.getName(), normalized)
                || contains(space.getDescription(), normalized)
                || contains(payloadBuilder.extractPrimaryGoal(space.getGoals()), normalized);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Data
    public static class ListLearningSpacesRequest {
        @dev.langchain4j.model.output.structured.Description("空间状态：active/archived/completed。可选，不传则查询全部未删除空间")
        private String status;

        @dev.langchain4j.model.output.structured.Description("空间类型：course/exam/project/career/competition。可选")
        private String type;

        @dev.langchain4j.model.output.structured.Description("关键词，可匹配空间名称、描述或主要目标。可选")
        private String keyword;

        @dev.langchain4j.model.output.structured.Description("风险状态：good/normal/warning/danger。可选")
        private String riskStatus;
    }
}
