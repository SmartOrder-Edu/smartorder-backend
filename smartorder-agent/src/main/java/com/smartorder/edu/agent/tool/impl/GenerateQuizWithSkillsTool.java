package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.service.ITaskService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * 基于 Skill 体系的出题工具——根据题型按需加载对应 Skill 生成题目。
 *
 * <p>与 {@link GeneratePracticeSetTool} 的区别：本工具通过 Skill 体系
 *（Registry → Loader → Assembler）组装 Prompt，而非直接读 prompts/*.txt。
 *
 * <p>后端委托给 {@link IEvaluationQuestionService#generatePracticeSet}，
 * 该方法内部的 buildUserPrompt() 在 skill-enabled=true 时走 Skill 路径。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateQuizWithSkillsTool implements ToolHandler {

    private static final int DEFAULT_QUESTION_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 3;

    private final IEvaluationQuestionService evaluationQuestionService;
    private final ITaskService taskService;

    @Override
    public String name() {
        return "generateQuizWithSkills";
    }

    @Override
    public String description() {
        return "按题型按需加载 Skill 生成一组可直接作答的练习题并创建学习任务。"
                + "支持单选题(single_choice)、多选题(multiple_choice)、"
                + "填空题(fill_blank)、判断题(judge)、编程题(code)、"
                + "SQL题(sql)、场景题(scenario)、简答题(short_answer)、多模态题(multimodal)。"
                + "可指定每种题型的数量和难度";
    }

    @Override
    public Class<?> requestType() {
        return GenerateQuizWithSkillsRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.QUIZ;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GenerateQuizWithSkillsRequest req = (GenerateQuizWithSkillsRequest) request;
        Long userId = context.getUserId();

        GenerateQuestionDTO dto = new GenerateQuestionDTO();
        dto.setLearningSpaceId(req.getSpaceId());
        dto.setChapterId(req.getChapterId());
        dto.setKnowledgePointId(req.getKnowledgePointId());
        dto.setCourseName(req.getCourseName());
        dto.setTopic(req.getTopic());
        dto.setQuestionTypeCountList(toQuestionTypeCounts(req));

        IEvaluationQuestionService.PracticeSetGenerationResult generated =
                evaluationQuestionService.generatePracticeSet(dto, userId);

        Task task = createBoundTask(userId, req, generated);

        log.info("[GenerateQuizWithSkills] userId={}, spaceId={}, taskId={}, "
                        + "questionCount={}, types={}",
                userId, req.getSpaceId(), task.getId(),
                generated.getQuestionCount(),
                dto.getQuestionTypeCountList().stream()
                        .map(GenerateQuestionDTO.QuestionTypeCountDTO::getQuestionType)
                        .toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "practice_set_created");
        result.put("summary", "已通过Skill体系生成练习任务：" + task.getTitle());
        result.put("learningSpaceId", req.getSpaceId());
        result.put("taskId", task.getId());
        result.put("generationTaskId", generated.getGenerationTaskId());
        result.put("sessionId", generated.getSessionId());
        result.put("title", task.getTitle());
        result.put("topic", generated.getTopic());
        result.put("questionCount", generated.getQuestionCount());
        result.put("totalScore", generated.getTotalScore());
        result.put("targetType", task.getTargetType());
        result.put("targetId", task.getTargetId());
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法生成练习任务，请检查学习空间、知识点、题型和难度设置是否有效";
    }

    // ======================== 请求参数转换 ========================

    private List<GenerateQuestionDTO.QuestionTypeCountDTO> toQuestionTypeCounts(
            GenerateQuizWithSkillsRequest req) {
        if (req.getQuestionTypeCountList() != null && !req.getQuestionTypeCountList().isEmpty()) {
            return req.getQuestionTypeCountList().stream().map(item -> {
                GenerateQuestionDTO.QuestionTypeCountDTO dto =
                        new GenerateQuestionDTO.QuestionTypeCountDTO();
                dto.setQuestionType(nonBlank(item.getQuestionType(), "single_choice"));
                dto.setCount(resolvePositive(item.getCount(), DEFAULT_QUESTION_COUNT));
                dto.setDifficulty(resolveDifficulty(item.getDifficulty(), req.getDifficulty()));
                dto.setOptionCount(item.getOptionCount());
                dto.setProgrammingLanguage(item.getProgrammingLanguage());
                dto.setSort(item.getSort());
                return dto;
            }).toList();
        }

        GenerateQuestionDTO.QuestionTypeCountDTO dto = new GenerateQuestionDTO.QuestionTypeCountDTO();
        dto.setQuestionType(nonBlank(req.getQuestionType(), "single_choice"));
        dto.setCount(resolvePositive(req.getQuestionCount(), DEFAULT_QUESTION_COUNT));
        dto.setDifficulty(resolveDifficulty(req.getDifficulty(), null));
        dto.setOptionCount(req.getOptionCount());
        dto.setProgrammingLanguage(req.getProgrammingLanguage());
        return List.of(dto);
    }

    private Task createBoundTask(Long userId, GenerateQuizWithSkillsRequest req,
                                  IEvaluationQuestionService.PracticeSetGenerationResult generated) {
        CreateTaskDTO taskDTO = new CreateTaskDTO();
        taskDTO.setSpaceId(req.getSpaceId());
        taskDTO.setPathNodeId(req.getPathNodeId());
        taskDTO.setTitle(nonBlank(req.getTaskTitle(), generated.getTopic()));
        taskDTO.setDescription(nonBlank(req.getTaskDescription(),
                "完成这组由AI生成的练习并查看判分结果。"));
        taskDTO.setTaskType(nonBlank(req.getTaskType(), "daily"));
        taskDTO.setActionType("practice_set");
        taskDTO.setTargetType("practice_session");
        taskDTO.setTargetId(generated.getSessionId());
        taskDTO.setPriority(req.getPriority());
        taskDTO.setDueDate(parseDate(req.getDueDate()));
        taskDTO.setDueTime(parseTime(req.getDueTime()));
        taskDTO.setEstimatedDurationMinutes(req.getEstimatedDurationMinutes());
        taskDTO.setAiSuggestion(req.getAiSuggestion());
        taskDTO.setAgentReason(req.getAgentReason());
        taskDTO.setMetadata(JSON.toJSONString(metadata(req, generated)));
        return taskService.createTask(userId, taskDTO);
    }

    private Map<String, Object> metadata(GenerateQuizWithSkillsRequest req,
                                          IEvaluationQuestionService.PracticeSetGenerationResult generated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "agent_generate_quiz_skills");
        metadata.put("generationTaskId", generated.getGenerationTaskId());
        metadata.put("practiceSessionId", generated.getSessionId());
        metadata.put("questionCount", generated.getQuestionCount());
        metadata.put("totalScore", generated.getTotalScore());
        metadata.put("topic", generated.getTopic());
        return metadata;
    }

    // ======================== 辅助方法 ========================

    private int resolvePositive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int resolveDifficulty(Integer value, Integer fallback) {
        Integer resolved = value != null ? value : fallback;
        if (resolved == null) return DEFAULT_DIFFICULTY;
        return Math.max(1, Math.min(5, resolved));
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value.trim());
    }

    // ======================== 请求 DTO ========================

    @Data
    public static class GenerateQuizWithSkillsRequest {
        @Description("学习空间ID，必填")
        private Long spaceId;
        @Description("学习路径节点ID，可选")
        private Long pathNodeId;
        @Description("评测模块章节ID，可选")
        private Long chapterId;
        @Description("评测模块知识点ID，可选")
        private Long knowledgePointId;
        @Description("课程或空间名称，可选，不传时从学习空间读取")
        private String courseName;
        @Description("练习主题，必填，例如：进程同步与信号量机制")
        private String topic;
        @Description("任务标题，可选，不传时使用生成的练习标题")
        private String taskTitle;
        @Description("任务说明，可选")
        private String taskDescription;
        @Description("计划类型：daily/review/remedial/exam/milestone/project，默认daily")
        private String taskType;
        @Description("单题型模式下的题型编码：single_choice/multiple_choice/fill_blank/judge/code/sql/scenario/short_answer/multimodal")
        private String questionType;
        @Description("单题型模式下的题目数量，默认5")
        private Integer questionCount;
        @Description("整体默认难度：1-5，默认3")
        private Integer difficulty;
        @Description("选择题选项数量，默认由评测服务处理")
        private Integer optionCount;
        @Description("代码题编程语言，默认C")
        private String programmingLanguage;
        @Description("多题型生成配置，传入后优先于questionType/questionCount")
        private List<QuestionTypeCountRequest> questionTypeCountList;
        @Description("优先级：1最高，5最低")
        private Integer priority;
        @Description("截止日期，格式YYYY-MM-DD，可选")
        private String dueDate;
        @Description("截止时间，格式HH:mm:ss或HH:mm，可选")
        private String dueTime;
        @Description("预估完成时长，单位分钟")
        private Integer estimatedDurationMinutes;
        @Description("Agent安排该练习的原因")
        private String agentReason;
        @Description("给用户的练习建议")
        private String aiSuggestion;
    }

    @Data
    public static class QuestionTypeCountRequest {
        @Description("题型编码：single_choice/multiple_choice/fill_blank/judge/code/sql/scenario/short_answer/multimodal")
        private String questionType;
        @Description("生成数量")
        private Integer count;
        @Description("难度：1-5")
        private Integer difficulty;
        @Description("选择题选项数量")
        private Integer optionCount;
        @Description("代码题编程语言")
        private String programmingLanguage;
        @Description("排序")
        private Integer sort;
    }
}
