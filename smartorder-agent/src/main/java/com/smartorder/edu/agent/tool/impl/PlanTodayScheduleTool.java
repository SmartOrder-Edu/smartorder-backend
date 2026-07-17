package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.domain.entity.LearningRecord;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.dto.CreateTaskDTO;
import com.smartorder.edu.learning.dto.PlanTodayTasksDTO;
import com.smartorder.edu.learning.dto.ResourceGenerationRequest;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.service.ILearningRecordService;
import com.smartorder.edu.learning.service.ITaskService;
import com.smartorder.edu.learning.service.ResourceGenerationResult;
import com.smartorder.edu.learning.service.ResourceGenerationService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 总学习 Agent 的今日任务编排工具。
 *
 * <p>learning 模块只负责创建通用学习任务；本工具位于 agent 模块，
 * 可以安全调用 student 测评模块，为练习/测评任务创建真实 EvaluationSession。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanTodayScheduleTool implements ToolHandler {

    private static final int DEFAULT_PRACTICE_COUNT = 1;
    private static final int DEFAULT_QUESTION_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 3;

    private final ITaskService taskService;
    private final IEvaluationQuestionService evaluationQuestionService;
    private final ResourceGenerationService resourceGenerationService;
    private final ILearningRecordService learningRecordService;
    private final LearningSpaceMapper learningSpaceMapper;

    @Override
    public String name() {
        return "planTodaySchedule";
    }

    @Override
    public String description() {
        return "为用户生成今天的可执行任务日程。先创建学习脚本类任务；如需要练习或测评，会调用测评模块生成真实答题会话并创建绑定任务";
    }

    @Override
    public Class<?> requestType() {
        return PlanTodayScheduleRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PLAN;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        PlanTodayScheduleRequest req = request == null ? new PlanTodayScheduleRequest() : (PlanTodayScheduleRequest) request;
        Long userId = context.getUserId();
        Map<String, Object> evidence = buildEvidence(userId, req);

        PlanTodayTasksDTO dto = new PlanTodayTasksDTO();
        dto.setMaxTasks(req.getMaxTasks());
        dto.setTotalAvailableMinutes(req.getTotalAvailableMinutes());
        dto.setStrategy(req.getStrategy());
        dto.setAllowedActionTypes(List.of("learn_script"));

        List<Task> learningTasks = taskService.planTodayTasks(userId, dto);
        List<Task> practiceSourceTasks = resolvePracticeSourceTasks(userId, learningTasks);
        List<Task> resourceTasks = Boolean.TRUE.equals(req.getCreateResourceTasks())
                ? createResourceTasks(userId, req, practiceSourceTasks)
                : List.of();
        List<Task> practiceTasks = Boolean.TRUE.equals(req.getCreatePracticeTasks())
                ? createPracticeTasks(userId, req, practiceSourceTasks)
                : List.of();

        List<Task> allTasks = new ArrayList<>();
        allTasks.addAll(learningTasks);
        allTasks.addAll(resourceTasks);
        allTasks.addAll(practiceTasks);

        log.info("[PlanTodaySchedule] userId={}, learningTasks={}, resourceTasks={}, practiceTasks={}, strategy={}",
                userId, learningTasks.size(), resourceTasks.size(), practiceTasks.size(), req.getStrategy());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "today_schedule_created");
        result.put("summary", buildSummary(learningTasks, resourceTasks, practiceTasks));
        result.put("date", LocalDate.now().toString());
        result.put("strategy", req.getStrategy() == null ? "deadline_first" : req.getStrategy());
        result.put("evidence", evidence);
        result.put("processSteps", buildProcessSteps(req, evidence, learningTasks, resourceTasks, practiceTasks));
        result.put("learningTaskCount", learningTasks.size());
        result.put("resourceTaskCount", resourceTasks.size());
        result.put("practiceTaskCount", practiceTasks.size());
        result.put("createdTaskCount", allTasks.size());
        result.put("tasks", allTasks.stream().map(this::toTaskPayload).toList());
        result.put("taskReasons", allTasks.stream().map(task -> taskReasonPayload(task, evidence)).toList());
        result.put("actions", allTasks.stream().map(this::startAction).toList());
        return result;
    }

    @Override
    public String userFriendlyErrorMessage() {
        return "暂时无法生成今日任务日程，请检查学习空间、学习路径和测评配置是否有效";
    }

    private List<Task> createPracticeTasks(Long userId, PlanTodayScheduleRequest req, List<Task> learningTasks) {
        int count = Math.max(1, Math.min(3, req.getPracticeTaskCount() == null ? DEFAULT_PRACTICE_COUNT : req.getPracticeTaskCount()));
        int questionCount = Math.max(1, Math.min(10, req.getPracticeQuestionCount() == null ? DEFAULT_QUESTION_COUNT : req.getPracticeQuestionCount()));
        int difficulty = Math.max(1, Math.min(5, req.getPracticeDifficulty() == null ? DEFAULT_DIFFICULTY : req.getPracticeDifficulty()));

        List<Task> created = new ArrayList<>();
        for (Task learningTask : learningTasks) {
            if (created.size() >= count) {
                break;
            }
            String topic = resolveTopic(req, learningTask);
            IEvaluationQuestionService.PracticeSetGenerationResult generated =
                    evaluationQuestionService.generatePracticeSet(buildQuestionRequest(learningTask, topic, questionCount, difficulty), userId);
            created.add(createBoundPracticeTask(userId, req, learningTask, generated, topic, questionCount, difficulty, created.size()));
        }
        return created;
    }

    private List<Task> createResourceTasks(Long userId, PlanTodayScheduleRequest req, List<Task> sourceTasks) {
        List<String> resourceTypes = normalizeResourceTypes(req.getResourceTypes());
        int maxResourceTasks = Math.max(1, Math.min(6,
                req.getResourceTaskCount() == null ? resourceTypes.size() : req.getResourceTaskCount()));
        List<Task> created = new ArrayList<>();

        for (Task sourceTask : sourceTasks) {
            for (String requestedType : resourceTypes) {
                if (created.size() >= maxResourceTasks) {
                    return created;
                }
                ResourceGenerationResult generated = resourceGenerationService.generateNew(
                        userId,
                        buildResourceRequest(sourceTask, requestedType, req)
                );
                created.add(createBoundResourceTask(userId, req, sourceTask, generated, requestedType, created.size()));
            }
        }
        return created;
    }

    private ResourceGenerationRequest buildResourceRequest(Task sourceTask, String requestedType, PlanTodayScheduleRequest req) {
        ResourceGenerationRequest request = new ResourceGenerationRequest();
        request.setSpaceId(sourceTask.getSpaceId());
        request.setTaskId(sourceTask.getId());
        request.setPathNodeId(sourceTask.getPathNodeId());
        request.setTopic(resolveTopic(req, sourceTask));
        request.setResourceType(toGenerationResourceType(requestedType));
        request.setPackageMode("today_schedule");
        request.setTargetMinutes(req.getResourceTargetMinutes());
        request.setUserIntent(resourceUserIntent(requestedType));
        return request;
    }

    private Task createBoundResourceTask(
            Long userId,
            PlanTodayScheduleRequest req,
            Task sourceTask,
            ResourceGenerationResult generated,
            String requestedType,
            int index
    ) {
        String actionType = isVideoResource(requestedType) ? "watch_video" : "read_resource";
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setSpaceId(sourceTask.getSpaceId());
        dto.setPathNodeId(sourceTask.getPathNodeId());
        dto.setTitle(resourceTaskTitle(actionType, generated.resource().getTitle()));
        dto.setDescription(generated.resource().getDescription());
        dto.setTaskType("daily");
        dto.setActionType(actionType);
        dto.setTargetType("resource");
        dto.setTargetId(generated.resource().getId());
        dto.setPriority(Math.max(1, Math.min(5, sourceTask.getPriority() == null ? 3 : sourceTask.getPriority() + 1)));
        dto.setDueDate(LocalDate.now());
        dto.setDueTime(defaultResourceDueTime(req, index));
        dto.setEstimatedDurationMinutes(resolveResourceMinutes(req));
        dto.setAiSuggestion(isVideoResource(requestedType)
                ? "先观看推荐入口中的合适讲解，再回到学习任务复盘关键点。"
                : "先快速浏览结构，再重点阅读不熟悉的部分。");
        dto.setAgentReason("总学习 Agent 根据今日学习任务补充了可直接打开的学习资源。");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "plan_today_schedule");
        metadata.put("linkedLearningTaskId", sourceTask.getId());
        metadata.put("resourceId", generated.resource().getId());
        metadata.put("requestedResourceType", requestedType);
        metadata.put("resourceType", generated.resource().getResourceType());
        metadata.put("resourceUrl", generated.resource().getUrl());
        dto.setMetadata(JSON.toJSONString(metadata));
        return taskService.createTask(userId, dto);
    }

    private List<String> normalizeResourceTypes(List<String> resourceTypes) {
        if (resourceTypes == null || resourceTypes.isEmpty()) {
            return List.of("doc", "mind_map");
        }
        return resourceTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase().replace("-", "_"))
                .distinct()
                .limit(6)
                .toList();
    }

    private String toGenerationResourceType(String requestedType) {
        if ("bilibili_video".equals(requestedType) || "b站视频".equals(requestedType)) {
            return "link";
        }
        return requestedType;
    }

    private String resourceUserIntent(String requestedType) {
        if (isVideoResource(requestedType)) {
            return "推荐B站视频学习入口，避免编造具体视频链接";
        }
        return "今日任务配套学习资源";
    }

    private boolean isVideoResource(String requestedType) {
        return "video".equals(requestedType)
                || "link".equals(requestedType)
                || "bilibili_video".equals(requestedType)
                || "b站视频".equals(requestedType);
    }

    private String resourceTaskTitle(String actionType, String resourceTitle) {
        return ("watch_video".equals(actionType) ? "观看：" : "阅读：") + resourceTitle;
    }

    private LocalTime defaultResourceDueTime(PlanTodayScheduleRequest req, int index) {
        if (req.getResourceDueTime() != null && !req.getResourceDueTime().isBlank()) {
            return LocalTime.parse(req.getResourceDueTime().trim());
        }
        return LocalTime.of(Math.min(21, 14 + index), 0);
    }

    private int resolveResourceMinutes(PlanTodayScheduleRequest req) {
        Integer minutes = req.getResourceTargetMinutes();
        if (minutes == null || minutes <= 0) {
            return 20;
        }
        return Math.max(5, Math.min(120, minutes));
    }

    private List<Task> resolvePracticeSourceTasks(Long userId, List<Task> newlyCreatedLearningTasks) {
        if (!newlyCreatedLearningTasks.isEmpty()) {
            return newlyCreatedLearningTasks;
        }
        return taskService.getTodayTasks(userId).stream()
                .filter(task -> "learn_script".equals(task.getActionType()))
                .filter(task -> task.getSpaceId() != null)
                .toList();
    }

    private GenerateQuestionDTO buildQuestionRequest(Task learningTask, String topic, int questionCount, int difficulty) {
        GenerateQuestionDTO dto = new GenerateQuestionDTO();
        dto.setLearningSpaceId(learningTask.getSpaceId());
        dto.setTopic(topic);

        GenerateQuestionDTO.QuestionTypeCountDTO item = new GenerateQuestionDTO.QuestionTypeCountDTO();
        item.setQuestionType("single_choice");
        item.setCount(questionCount);
        item.setDifficulty(difficulty);
        item.setOptionCount(4);
        dto.setQuestionTypeCountList(List.of(item));
        return dto;
    }

    private Task createBoundPracticeTask(
            Long userId,
            PlanTodayScheduleRequest req,
            Task learningTask,
            IEvaluationQuestionService.PracticeSetGenerationResult generated,
            String topic,
            int questionCount,
            int difficulty,
            int index
    ) {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setSpaceId(learningTask.getSpaceId());
        dto.setPathNodeId(learningTask.getPathNodeId());
        String actionType = normalizeEvaluationActionType(req.getEvaluationTaskType());
        boolean assessment = "assessment".equals(actionType);
        dto.setTitle((assessment ? "测评：" : "练习：") + topic);
        dto.setDescription(assessment ? "完成这次测评并查看能力报告。" : "完成这组练习并查看判分结果。");
        dto.setTaskType("daily");
        dto.setActionType(actionType);
        dto.setTargetType(assessment ? "assessment_session" : "practice_session");
        dto.setTargetId(generated.getSessionId());
        dto.setPriority(Math.max(1, Math.min(5, learningTask.getPriority() == null ? 3 : learningTask.getPriority() + 1)));
        dto.setDueDate(LocalDate.now());
        dto.setDueTime(defaultPracticeDueTime(req, index));
        dto.setEstimatedDurationMinutes(Math.max(10, questionCount * 3));
        dto.setAiSuggestion(assessment ? "按正式测评节奏完成，提交后查看能力报告。" : "先独立完成，再查看判分结果和错因分析。");
        dto.setAgentReason(assessment
                ? "总学习 Agent 根据今日学习任务追加一次测评，用于验证当前阶段掌握情况。"
                : "总学习 Agent 根据今日学习任务追加一次即时练习，用于验证刚学习内容的掌握情况。");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "plan_today_schedule");
        metadata.put("linkedLearningTaskId", learningTask.getId());
        metadata.put("practiceSessionId", generated.getSessionId());
        metadata.put("generationTaskId", generated.getGenerationTaskId());
        metadata.put("questionCount", questionCount);
        metadata.put("difficulty", difficulty);
        metadata.put("topic", topic);
        metadata.put("evaluationTaskType", actionType);
        dto.setMetadata(JSON.toJSONString(metadata));
        return taskService.createTask(userId, dto);
    }

    private String normalizeEvaluationActionType(String value) {
        if ("assessment".equals(value)) {
            return "assessment";
        }
        return "practice_set";
    }

    private LocalTime defaultPracticeDueTime(PlanTodayScheduleRequest req, int index) {
        if (req.getPracticeDueTime() != null && !req.getPracticeDueTime().isBlank()) {
            return LocalTime.parse(req.getPracticeDueTime().trim());
        }
        return LocalTime.of(Math.min(21, 18 + index), 0);
    }

    private String resolveTopic(PlanTodayScheduleRequest req, Task learningTask) {
        if (req.getPracticeTopic() != null && !req.getPracticeTopic().isBlank()) {
            return req.getPracticeTopic().trim();
        }
        String title = learningTask.getTitle() == null ? "当前知识点" : learningTask.getTitle().trim();
        return title.replaceFirst("^学习[:：]\\s*", "");
    }

    private String buildSummary(List<Task> learningTasks, List<Task> resourceTasks, List<Task> practiceTasks) {
        return "已创建 " + learningTasks.size() + " 个学习任务"
                + (resourceTasks.isEmpty() ? "" : "，补充 " + resourceTasks.size() + " 个资源任务")
                + (practiceTasks.isEmpty() ? "" : "，并追加 " + practiceTasks.size() + " 个练习测评任务");
    }

    private Map<String, Object> buildEvidence(Long userId, PlanTodayScheduleRequest req) {
        int recentDays = 7;
        List<LearningRecord> records = learningRecordService.getRecentByUserId(userId, recentDays);
        List<Task> pendingTasks = taskService.getPendingTasks(userId);
        List<Task> overdueTasks = taskService.getOverdueTasks(userId);
        List<Task> todayTasks = taskService.getTodayTasks(userId);

        Set<Long> spaceIds = new HashSet<>();
        records.stream().map(LearningRecord::getSpaceId).filter(Objects::nonNull).forEach(spaceIds::add);
        pendingTasks.stream().map(Task::getSpaceId).filter(Objects::nonNull).limit(10).forEach(spaceIds::add);
        todayTasks.stream().map(Task::getSpaceId).filter(Objects::nonNull).limit(10).forEach(spaceIds::add);
        Map<Long, String> spaceNames = resolveSpaceNames(spaceIds);

        int studyMinutes = records.stream().map(LearningRecord::getStudyDurationMinutes)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int completed = records.stream().map(LearningRecord::getTasksCompleted)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int planned = records.stream().map(LearningRecord::getTasksPlanned)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        double completionRate = planned <= 0 ? 0 : Math.round(completed * 1000.0 / planned) / 10.0;

        List<Integer> focusValues = records.stream().map(LearningRecord::getFocusLevel)
                .filter(Objects::nonNull).toList();
        Double averageFocus = focusValues.isEmpty()
                ? null
                : Math.round(focusValues.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;

        List<Integer> selfRatings = records.stream().map(LearningRecord::getSelfRating)
                .filter(Objects::nonNull).toList();
        Double averageSelfRating = selfRatings.isEmpty()
                ? null
                : Math.round(selfRatings.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;

        List<String> recentSpaces = records.stream()
                .filter(record -> record.getSpaceId() != null)
                .sorted((a, b) -> {
                    LocalDate left = a.getRecordDate() == null ? LocalDate.MIN : a.getRecordDate();
                    LocalDate right = b.getRecordDate() == null ? LocalDate.MIN : b.getRecordDate();
                    return right.compareTo(left);
                })
                .map(record -> spaceNames.getOrDefault(record.getSpaceId(), "学习空间 " + record.getSpaceId()))
                .distinct()
                .limit(5)
                .toList();

        List<String> weakSignals = buildWeakSignals(records, overdueTasks.size(), planned, completionRate, averageFocus, averageSelfRating);
        List<String> decisionBasis = buildDecisionBasis(req, records, todayTasks, pendingTasks, overdueTasks, weakSignals);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("recentDays", recentDays);
        evidence.put("recentRecordCount", records.size());
        evidence.put("recentSpaces", recentSpaces);
        evidence.put("recentStudyMinutes", studyMinutes);
        evidence.put("recentCompletedTasks", completed);
        evidence.put("recentPlannedTasks", planned);
        evidence.put("recentCompletionRate", completionRate);
        evidence.put("averageFocusLevel", averageFocus);
        evidence.put("averageSelfRating", averageSelfRating);
        evidence.put("todayExistingTaskCount", todayTasks.size());
        evidence.put("unfinishedTaskCount", pendingTasks.size());
        evidence.put("overdueTaskCount", overdueTasks.size());
        evidence.put("availableMinutes", normalizeDisplayMinutes(req.getTotalAvailableMinutes()));
        evidence.put("strategy", req.getStrategy() == null ? "deadline_first" : req.getStrategy());
        evidence.put("weakSignals", weakSignals);
        evidence.put("decisionBasis", decisionBasis);
        return evidence;
    }

    private Map<Long, String> resolveSpaceNames(Set<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Map.of();
        }
        return learningSpaceMapper.selectBatchIds(spaceIds).stream()
                .filter(space -> space.getId() != null)
                .collect(Collectors.toMap(
                        space -> space.getId(),
                        space -> space.getName() == null || space.getName().isBlank()
                                ? "学习空间 " + space.getId()
                                : space.getName(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private List<String> buildWeakSignals(
            List<LearningRecord> records,
            int overdueCount,
            int planned,
            double completionRate,
            Double averageFocus,
            Double averageSelfRating
    ) {
        List<String> signals = new ArrayList<>();
        if (overdueCount > 0) {
            signals.add("存在 " + overdueCount + " 个逾期任务，需要优先处理学习节奏风险");
        }
        if (planned > 0 && completionRate < 60) {
            signals.add("最近任务完成率为 " + completionRate + "%，今日安排需要控制任务量");
        }
        if (averageFocus != null && averageFocus < 3) {
            signals.add("最近专注度均值为 " + averageFocus + "，适合拆成较短任务");
        }
        if (averageSelfRating != null && averageSelfRating < 3) {
            signals.add("最近自评掌握度偏低，适合增加即时练习验证");
        }
        records.stream()
                .map(LearningRecord::getAiReflection)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .limit(2)
                .forEach(signals::add);
        if (signals.isEmpty()) {
            signals.add(records.isEmpty()
                    ? "最近 7 天暂无学习记录，优先依据活跃学习空间、学习路径和未完成任务编排"
                    : "最近学习记录稳定，今日任务以推进学习路径为主");
        }
        return signals;
    }

    private List<String> buildDecisionBasis(
            PlanTodayScheduleRequest req,
            List<LearningRecord> records,
            List<Task> todayTasks,
            List<Task> pendingTasks,
            List<Task> overdueTasks,
            List<String> weakSignals
    ) {
        List<String> basis = new ArrayList<>();
        basis.add("读取最近 7 天学习记录 " + records.size() + " 条");
        basis.add("检查今日已有任务 " + todayTasks.size() + " 个、未完成任务 " + pendingTasks.size() + " 个、逾期任务 " + overdueTasks.size() + " 个");
        basis.add("按「" + strategyLabel(req.getStrategy()) + "」策略选择学习空间和学习路径下一节点");
        if (Boolean.TRUE.equals(req.getCreateResourceTasks())) {
            basis.add("为学习任务补充文档、思维导图或视频入口等配套资源");
        }
        if (Boolean.TRUE.equals(req.getCreatePracticeTasks())) {
            basis.add("为今日学习内容追加练习或测评，用于验证掌握情况");
        }
        if (!weakSignals.isEmpty()) {
            basis.add("参考风险信号：" + weakSignals.get(0));
        }
        return basis;
    }

    private List<Map<String, Object>> buildProcessSteps(
            PlanTodayScheduleRequest req,
            Map<String, Object> evidence,
            List<Task> learningTasks,
            List<Task> resourceTasks,
            List<Task> practiceTasks
    ) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(processStep("analyze_recent_learning", "分析最近学习记录", "completed",
                "读取最近 " + evidence.get("recentDays") + " 天学习记录、今日任务和未完成任务"));
        steps.add(processStep("identify_focus", "识别编排重点", "completed",
                firstWeakSignal(evidence)));
        steps.add(processStep("match_learning_path", "匹配学习路径", "completed",
                learningTasks.isEmpty()
                        ? "没有发现可新建的学习脚本任务，可能今日已有待完成任务或路径节点已排完"
                        : "已选择 " + learningTasks.size() + " 个学习路径节点"));
        if (Boolean.TRUE.equals(req.getCreateResourceTasks())) {
            steps.add(processStep("create_resources", "创建配套资源任务", "completed",
                    resourceTasks.isEmpty() ? "未创建新的资源任务" : "已创建 " + resourceTasks.size() + " 个资源任务"));
        }
        if (Boolean.TRUE.equals(req.getCreatePracticeTasks())) {
            steps.add(processStep("create_practice", "创建练习测评任务", "completed",
                    practiceTasks.isEmpty() ? "未创建新的练习测评任务" : "已创建 " + practiceTasks.size() + " 个练习测评任务"));
        }
        steps.add(processStep("finalize_schedule", "整理今日任务说明", "completed",
                "已写入每个任务的 Agent 安排理由"));
        return steps;
    }

    @SuppressWarnings("unchecked")
    private String firstWeakSignal(Map<String, Object> evidence) {
        Object value = evidence.get("weakSignals");
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return "未发现明显风险信号，按学习路径稳定推进";
    }

    private Map<String, Object> processStep(String key, String label, String status, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", key);
        step.put("label", label);
        step.put("status", status);
        step.put("detail", detail);
        return step;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskReasonPayload(Task task, Map<String, Object> evidence) {
        List<String> evidenceTags = new ArrayList<>();
        evidenceTags.add(actionTypeLabel(task.getActionType()));
        evidenceTags.add(strategyLabel((String) evidence.get("strategy")));
        Object weakSignals = evidence.get("weakSignals");
        if (weakSignals instanceof List<?> list && !list.isEmpty()) {
            evidenceTags.add(String.valueOf(list.get(0)));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getId());
        payload.put("title", task.getTitle());
        payload.put("reason", task.getAgentReason());
        payload.put("evidenceTags", evidenceTags);
        return payload;
    }

    private String actionTypeLabel(String actionType) {
        if ("practice_set".equals(actionType)) {
            return "即时练习";
        }
        if ("assessment".equals(actionType)) {
            return "阶段测评";
        }
        if ("watch_video".equals(actionType)) {
            return "视频资源";
        }
        if ("read_resource".equals(actionType)) {
            return "阅读资源";
        }
        return "学习路径";
    }

    private String strategyLabel(String strategy) {
        if ("weakness_first".equals(strategy)) {
            return "薄弱点优先";
        }
        if ("balanced".equals(strategy)) {
            return "均衡分配";
        }
        return "截止时间优先";
    }

    private int normalizeDisplayMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return 90;
        }
        return Math.max(5, Math.min(600, minutes));
    }

    private Map<String, Object> toTaskPayload(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getId());
        payload.put("spaceId", task.getSpaceId());
        payload.put("pathNodeId", task.getPathNodeId());
        payload.put("title", task.getTitle());
        payload.put("taskType", task.getTaskType());
        payload.put("actionType", task.getActionType());
        payload.put("targetType", task.getTargetType());
        payload.put("targetId", task.getTargetId());
        payload.put("priority", task.getPriority());
        payload.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
        payload.put("dueTime", task.getDueTime() == null ? null : task.getDueTime().toString());
        payload.put("estimatedDurationMinutes", task.getEstimatedDurationMinutes());
        payload.put("agentReason", task.getAgentReason());
        return payload;
    }

    private Map<String, Object> startAction(Task task) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("taskId", task.getId());
        target.put("spaceId", task.getSpaceId());
        target.put("targetType", task.getTargetType());
        target.put("targetId", task.getTargetId());

        Map<String, Object> action = new LinkedHashMap<>();
        if ("practice_set".equals(task.getActionType())) {
            action.put("type", "start_practice");
            action.put("label", "开始练习");
        } else if ("assessment".equals(task.getActionType())) {
            action.put("type", "start_assessment");
            action.put("label", "开始测评");
        } else if ("watch_video".equals(task.getActionType())) {
            action.put("type", "open_resource");
            action.put("label", "观看资源");
        } else if ("read_resource".equals(task.getActionType())) {
            action.put("type", "open_resource");
            action.put("label", "阅读资源");
        } else {
            action.put("type", "start_learning");
            action.put("label", "开始学习");
        }
        action.put("target", target);
        return action;
    }

    @Data
    public static class PlanTodayScheduleRequest {
        @Description("最多生成多少个学习任务，默认3，范围1-8")
        private Integer maxTasks;
        @Description("今日可用学习时间，单位分钟，范围5-600")
        private Integer totalAvailableMinutes;
        @Description("任务排序策略：deadline_first/weakness_first/balanced，默认deadline_first")
        private String strategy;
        @Description("是否在学习任务后追加练习测评任务，默认false")
        private Boolean createPracticeTasks;
        @Description("是否为今日学习任务追加资源任务，默认false")
        private Boolean createResourceTasks;
        @Description("资源类型列表，如doc/mind_map/bilibili_video；默认doc和mind_map")
        private List<String> resourceTypes;
        @Description("最多追加几个资源任务，默认按资源类型数量，范围1-6")
        private Integer resourceTaskCount;
        @Description("资源任务目标时长，单位分钟，默认20")
        private Integer resourceTargetMinutes;
        @Description("资源任务截止时间，格式HH:mm或HH:mm:ss；为空时从14:00开始排")
        private String resourceDueTime;
        @Description("追加任务类型：practice_set/assessment，默认practice_set")
        private String evaluationTaskType;
        @Description("最多追加几个练习任务，默认1，范围1-3")
        private Integer practiceTaskCount;
        @Description("练习题数量，默认5，范围1-10")
        private Integer practiceQuestionCount;
        @Description("练习难度，1-5，默认3")
        private Integer practiceDifficulty;
        @Description("指定练习主题；为空时使用学习任务标题对应的知识点")
        private String practiceTopic;
        @Description("练习任务截止时间，格式HH:mm或HH:mm:ss；为空时从18:00开始排")
        private String practiceDueTime;
    }
}
