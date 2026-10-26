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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResolveLearningSpaceTool implements ToolHandler {

    private static final int MAX_CANDIDATES = 5;

    private final ILearningSpaceService learningSpaceService;
    private final ISpaceDashboardService spaceDashboardService;
    private final LearningSpaceToolPayloadBuilder payloadBuilder;

    @Override
    public String name() {
        return "resolveLearningSpace";
    }

    @Override
    public String description() {
        return "根据用户自然语言提到的空间名称、课程名、考试名或学习目标，匹配当前用户的学习空间。"
                + "当用户说'高数那个空间'、'四级'、'Java路线'等但没有提供 spaceId 时，先调用此工具。"
                + "如果唯一命中，可继续调用 getLearningSpaceDashboard；如果多候选，应让用户选择。";
    }

    @Override
    public Class<?> requestType() {
        return ResolveLearningSpaceRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROGRESS;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ResolveLearningSpaceRequest req = (ResolveLearningSpaceRequest) request;
        Long userId = context.getUserId();
        String query = req.getQuery() == null ? "" : req.getQuery().trim();
        String status = req.getStatus() == null || req.getStatus().isBlank() ? "active" : req.getStatus().trim();

        log.info("[ResolveLearningSpace] userId={}, query={}, status={}", userId, query, status);

        List<LearningSpaceVO> candidates = learningSpaceService.listSpaces(userId, status).stream()
                .map(space -> Map.entry(space, score(space, query)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<LearningSpaceVO, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().getSortOrder() != null ? entry.getKey().getSortOrder() : 0))
                .limit(MAX_CANDIDATES)
                .map(Map.Entry::getKey)
                .toList();

        List<Map<String, Object>> summaries = candidates.stream()
                .map(space -> toSummary(userId, space))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("cardType", summaries.size() == 1 ? "learning_space_resolved" : "learning_space_list");
        result.put("query", query);
        result.put("matchStatus", summaries.isEmpty() ? "none" : summaries.size() == 1 ? "unique" : "multiple");
        result.put("summary", summaries.isEmpty()
                ? "没有找到与「" + query + "」匹配的学习空间"
                : summaries.size() == 1
                ? "已匹配到学习空间「" + summaries.get(0).get("name") + "」"
                : "找到 " + summaries.size() + " 个可能的学习空间，需要用户确认具体是哪一个");
        result.put("spaces", summaries);
        if (summaries.size() == 1) {
            result.put("space", summaries.get(0));
        }
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法匹配对应的学习空间";
    }

    private Map<String, Object> toSummary(Long userId, LearningSpaceVO space) {
        try {
            SpaceDashboardVO dashboard = spaceDashboardService.getDashboard(userId, space.getId());
            return payloadBuilder.toSpaceSummary(space, dashboard);
        } catch (Exception e) {
            log.warn("[ResolveLearningSpace] dashboard enrich failed, spaceId={}: {}",
                    space.getId(), e.getMessage());
            return payloadBuilder.toSpaceSummary(space, null);
        }
    }

    private int score(LearningSpaceVO space, String query) {
        if (query == null || query.isBlank()) return 1;
        String q = query.toLowerCase(Locale.ROOT);
        int score = 0;
        score += scoreField(space.getName(), q, 100, 60);
        score += scoreField(payloadBuilder.extractPrimaryGoal(space.getGoals()), q, 80, 40);
        score += scoreField(space.getDescription(), q, 40, 20);
        score += scoreField(space.getType(), q, 20, 10);
        return score;
    }

    private int scoreField(String value, String query, int exactScore, int containsScore) {
        if (value == null || value.isBlank()) return 0;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals(query)) return exactScore;
        if (normalized.contains(query) || query.contains(normalized)) return containsScore;
        return 0;
    }

    @Data
    public static class ResolveLearningSpaceRequest {
        @dev.langchain4j.model.output.structured.Description("用户提到的空间名称、课程、考试、项目或学习目标关键词，例如'高数'、'四级'、'Java后端'")
        private String query;

        @dev.langchain4j.model.output.structured.Description("空间状态过滤：active/archived/completed。默认 active")
        private String status;
    }
}
