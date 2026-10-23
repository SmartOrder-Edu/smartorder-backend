package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.service.ITaskService;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建可执行学习任务工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateLearningTaskTool implements ToolHandler {

    private final ITaskService taskService;

    @Override
    public String name() {
        return "createLearningTask";
    }

    @Override
    public String description() {
        return "创建一个可执行学习任务。任务必须属于某个学习空间，并通过actionType说明执行动作，例如学习脚本、资源阅读、专项练习、阶段测评、错题复盘或项目实践";
    }

    @Override
    public Class<?> requestType() {
        return CreateLearningTaskRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.TASK;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        CreateLearningTaskRequest req = (CreateLearningTaskRequest) request;
        Long userId = context.getUserId();

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setSpaceId(req.getSpaceId());
        dto.setPathNodeId(req.getPathNodeId());
        dto.setTitle(req.getTitle());
        dto.setDescription(req.getDescription());
        dto.setTaskType(req.getTaskType());
        dto.setActionType(req.getActionType());
        dto.setTargetType(req.getTargetType());
        dto.setTargetId(req.getTargetId());
        dto.setPriority(req.getPriority());
        dto.setDueDate(parseDate(req.getDueDate()));
        dto.setDueTime(parseTime(req.getDueTime()));
        dto.setEstimatedDurationMinutes(req.getEstimatedDurationMinutes());
        dto.setAiSuggestion(req.getAiSuggestion());
        dto.setAgentReason(req.getAgentReason());
        dto.setMetadata(req.getMetadata() == null || req.getMetadata().isEmpty() ? null : JSON.toJSONString(req.getMetadata()));

        Task task = taskService.createTask(userId, dto);
        log.info("[CreateLearningTask] userId={}, taskId={}, spaceId={}, actionType={}",
                userId, task.getId(), task.getSpaceId(), task.getActionType());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "learning_task_created");
        result.put("summary", "已创建学习任务：" + task.getTitle());
        result.put("taskId", task.getId());
        result.put("spaceId", task.getSpaceId());
        result.put("pathNodeId", task.getPathNodeId());
        result.put("title", task.getTitle());
        result.put("taskType", task.getTaskType());
        result.put("actionType", task.getActionType());
        result.put("targetType", task.getTargetType());
        result.put("targetId", task.getTargetId());
        result.put("priority", task.getPriority());
        result.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
        result.put("estimatedDurationMinutes", task.getEstimatedDurationMinutes());
        result.put("agentReason", task.getAgentReason());
        result.put("actions", buildActions(task));
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法创建学习任务，请检查学习空间、任务类型和时间安排是否有效";
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim());
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalTime.parse(value.trim());
    }

    private Object buildActions(Task task) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("taskId", task.getId());
        target.put("spaceId", task.getSpaceId());
        target.put("targetType", task.getTargetType());
        target.put("targetId", task.getTargetId());

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", startActionType(task.getActionType()));
        action.put("label", startActionLabel(task.getActionType()));
        action.put("target", target);
        return java.util.List.of(action);
    }

    private String startActionType(String actionType) {
        return switch (String.valueOf(actionType)) {
            case "read_resource", "watch_video" -> "open_resource";
            case "practice_set" -> "start_practice";
            case "assessment" -> "start_assessment";
            default -> "start_learning";
        };
    }

    private String startActionLabel(String actionType) {
        return switch (String.valueOf(actionType)) {
            case "read_resource" -> "阅读资源";
            case "watch_video" -> "观看讲解";
            case "practice_set" -> "开始练习";
            case "assessment" -> "开始测评";
            case "mistake_review" -> "复盘错题";
            case "project_practice" -> "开始实践";
            case "summary_reflection" -> "开始复盘";
            default -> "开始学习";
        };
    }

    @Data
    public static class CreateLearningTaskRequest {
        @Description("学习空间ID，必填")
        private Long spaceId;
        @Description("学习路径节点ID，可选，用于把任务绑定到阶段或知识点")
        private Long pathNodeId;
        @Description("任务标题，必填")
        private String title;
        @Description("任务说明，可选")
        private String description;
        @Description("计划类型：daily/milestone/exam/review/remedial/project，默认daily")
        private String taskType;
        @Description("执行动作类型：learn_script/read_resource/watch_video/practice_set/assessment/mistake_review/project_practice/summary_reflection")
        private String actionType;
        @Description("执行目标类型：lesson_bundle/resource/practice_session/assessment_session/knowledge_node/path_node等，可选")
        private String targetType;
        @Description("执行目标ID，可选")
        private Long targetId;
        @Description("优先级：1最高，5最低，默认3")
        private Integer priority;
        @Description("截止日期，格式YYYY-MM-DD，可选")
        private String dueDate;
        @Description("截止时间，格式HH:mm:ss或HH:mm，可选")
        private String dueTime;
        @Description("预估学习时长，单位分钟")
        private Integer estimatedDurationMinutes;
        @Description("Agent安排该任务的原因")
        private String agentReason;
        @Description("AI给学生的执行建议")
        private String aiSuggestion;
        @Description("扩展元数据，例如knowledgeNodeIds、resourceIds、questionCount、difficulty、expectedOutput")
        private Map<String, Object> metadata;
    }
}
