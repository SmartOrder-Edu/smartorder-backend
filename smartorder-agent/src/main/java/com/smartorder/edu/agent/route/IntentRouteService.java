package com.smartorder.edu.agent.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图路由服务——分层路由编排器
 * <p>
 * 路由优先级：状态检查 → 关键词规则 → LLM 分类（Phase 3）→ 槽位校验（Phase 4）→ 澄清兜底（Phase 5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteService {

    private final StateRuleRouter stateRuleRouter;
    private final LlmIntentClassifier llmIntentClassifier;
    private final SlotValidator slotValidator;
    private final ClarificationRouter clarificationRouter;

    /**
     * 分层路由（新 API）——结合会话状态进行路由
     */
    public IntentRouteResult route(RouteContext ctx) {
        IntentRouteResult result;

        // Layer 1: 状态感知路由（最高优先级）
        IntentRouteResult stateResult = stateRuleRouter.tryRoute(ctx);
        if (stateResult != null) {
            log.info("[IntentRoute] State命中: intent={}, mode={}, source={}",
                    stateResult.getIntentType(), stateResult.getRunMode().getCode(), stateResult.getSource());
            result = stateResult;
        } else {
            // Layer 2: 关键词规则路由
            IntentRouteResult ruleResult = routeByRule(ctx.getMessage());
            if (ruleResult != null) {
                log.info("[IntentRoute] Keyword命中: intent={}, mode={}",
                        ruleResult.getIntentType(), ruleResult.getRunMode().getCode());
                result = ruleResult;
            } else {
                // Layer 3: LLM 意图分类（语义兜底）
                LlmIntentClassifier.LlmIntentResult llmResult = llmIntentClassifier.classify(ctx.getMessage());
                if (llmResult != null && llmIntentClassifier.isConfident(llmResult)) {
                    log.info("[IntentRoute] LLM命中: intent={}, confidence={}", llmResult.intent(), llmResult.confidence());
                    result = buildResultFromLlm(llmResult, ctx.getMessage());
                } else if (llmResult != null && llmResult.intent() != IntentType.UNKNOWN && llmResult.confidence() > 0.0) {
                    log.info("[IntentRoute] LLM低置信: intent={}, confidence={}，默认 REACT",
                            llmResult.intent(), llmResult.confidence());
                    result = buildDefaultResult();
                } else {
                    log.info("[IntentRoute] 规则和LLM均未命中，使用默认 REACT 模式");
                    result = buildDefaultResult();
                }
            }
        }

        // Layer 4: 槽位校验（对非默认、非 UNKNOWN 结果进行槽位检查）
        result = applySlotCheck(result, ctx);

        // Layer 5: 低置信澄清兜底
        if (result.isNeedsClarification()) {
            applyClarification(result);
        }

        return result;
    }

    /**
     * 槽位校验：对需要槽位的意图进行检查，不足则降级
     */
    private IntentRouteResult applySlotCheck(IntentRouteResult result, RouteContext ctx) {
        if (result.getIntentEnum() == null || result.getIntentEnum() == IntentType.UNKNOWN) {
            return result; // 默认/未知意图不校验槽位
        }
        if (result.getSource() == RoutingSource.STATE) {
            return result; // State 路由已自带槽位判断，不重复校验
        }

        List<String> missingSlots = slotValidator.validate(result.getIntentEnum(), ctx);
        if (!missingSlots.isEmpty()) {
            log.info("[IntentRoute] Slot校验不通过: intent={}, missingSlots={}",
                    result.getIntentEnum(), missingSlots);
            result.setConfidence(Math.min(result.getConfidence(), 0.5));
            result.setNeedsClarification(true);
            result.setMissingSlots(missingSlots);
        }

        return result;
    }

    /**
     * 澄清兜底：将路由结果转为 REACT 澄清模式
     */
    private void applyClarification(IntentRouteResult result) {
        String clarificationMsg = clarificationRouter.buildClarification(
                result.getIntentEnum() != null ? result.getIntentEnum() : IntentType.UNKNOWN,
                result.getMissingSlots());
        result.setRunMode(AgentRunMode.REACT);
        result.setRequiredTools(List.of());
        result.setSource(RoutingSource.CLARIFICATION);
        result.setReason(clarificationMsg);
        log.info("[IntentRoute] 进入澄清模式: {}", clarificationMsg);
    }

    /**
     * 根据用户消息进行意图路由（兼容旧 API，无会话状态）
     */
    public IntentRouteResult route(String message) {
        return route(RouteContext.builder().message(message).build());
    }

    // ============ 高置信关键词白名单 ============

    // 资源生成关键词（高置信）
    private static final List<String> RESOURCE_GEN_KEYWORDS = List.of(
            "生成资料", "生成PPT", "测试题", "全套资料", "生成习题",
            "出题", "做一个PPT", "制作PPT", "生成笔记", "整理笔记",
            "复习资料", "学习资料", "讲义", "试卷", "练习题", "出一套题"
    );

    // 图表/格式化输出关键词：这类请求通常是普通回答指定输出格式，不应默认进入多智能体。
    private static final List<String> DIAGRAM_KEYWORDS = List.of(
            "mermaid", "Mermaid", "流程图", "技术图", "架构图", "时序图",
            "类图", "状态图", "用例图", "ER图", "UML", "sequenceDiagram",
            "flowchart", "graph TD"
    );

    // 明确规划请求关键词
    private static final List<String> PLANNING_KEYWORDS = List.of(
            "制定学习计划", "帮我规划", "备考计划", "准备考研",
            "学习路径怎么", "帮我安排", "帮我定制", "规划一下",
            "制定复习计划", "帮我制定"
    );

    // 答疑关键词
    private static final List<String> HELP_KEYWORDS = List.of(
            "不理解", "帮我讲讲", "讲一下", "不会做",
            "这题", "这道题", "帮我解释"
    );

    // 进度查询关键词
    private static final List<String> PROGRESS_KEYWORDS = List.of(
            "学习进度", "学习情况", "最近学了什么", "复习进度", "学习分析",
            "学得怎么样", "进度如何", "学习报告"
    );

    // 任务管理关键词
    private static final List<String> TASK_KEYWORDS = List.of(
            "添加任务", "标记完成", "修改任务", "今天学什么",
            "今日任务", "查看任务", "还有什么没完成"
    );

    // ============ 规则匹配 ============

    /**
     * 关键词规则匹配——仅处理高置信显式意图
     * <p>
     * 注意：已废弃的过宽关键词（如"学习"、"计划"、"不会"等单独两字词）
     * 不再出现在规则中，由 LLM 分类器（Phase 3）兜底。
     */
    private IntentRouteResult routeByRule(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        // 1. 资源生成类（最高置信度，显式命令词）→ PlanAndExecute
        if (containsAny(message, RESOURCE_GEN_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.RESOURCE_GENERATION, AgentRunMode.PLAN_AND_EXECUTE,
                    0.90, List.of("getStudentProfile", "queryKnowledge", "generateResource", "generateQuiz"),
                    "用户需要复杂资源生成");
        }

        // 2. 图表/格式化输出：例如 Mermaid 技术图，本质是单轮技术说明 + 指定输出格式
        if (containsAny(message, DIAGRAM_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.DIAGRAM_GENERATION, AgentRunMode.REACT,
                    0.88, List.of(),
                    "用户需要图表化技术说明，使用 REACT 直接回答");
        }

        // 3. 明确学习规划请求 → PlanAndExecute
        if (containsAny(message, PLANNING_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.GOAL_PLANNING, AgentRunMode.PLAN_AND_EXECUTE,
                    0.85, List.of("getStudentProfile"),
                    "用户明确要求制定学习计划");
        }

        // 4. 习题答疑类
        if (containsAny(message, HELP_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.PROBLEM_HELP, AgentRunMode.REACT,
                    0.85, List.of("getStudentProfile", "queryKnowledge", "queryTasks"),
                    "用户需要学习辅导");
        }

        // 5. 进度查询
        if (containsAny(message, PROGRESS_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.PROGRESS_REVIEW, AgentRunMode.REACT,
                    0.85, List.of("queryLearningProgress", "getStudentProfile"),
                    "用户查询学习进度");
        }

        // 6. 任务管理
        if (containsAny(message, TASK_KEYWORDS.toArray(new String[0]))) {
            return buildKeywordResult(IntentType.TASK_MANAGEMENT, AgentRunMode.REACT,
                    0.85, List.of("queryTasks", "manageLearningTask"),
                    "用户管理学习任务");
        }

        // 7. 简单知识问答（短消息 + 定义类关键词）
        if (message.length() <= 30 && containsAny(message, "什么是", "解释一下", "定义")) {
            return buildKeywordResult(IntentType.KNOWLEDGE_QA, AgentRunMode.REACT,
                    0.80, List.of(),
                    "简单知识解释");
        }

        return null;
    }

    private IntentRouteResult buildKeywordResult(IntentType intent, AgentRunMode mode,
                                                  double confidence, List<String> tools, String reason) {
        boolean needsPlanning = mode == AgentRunMode.PLAN_AND_EXECUTE;
        return IntentRouteResult.builder()
                .intentType(intent.getChineseName())
                .intentEnum(intent)
                .complexity("复杂")
                .runMode(mode)
                .requiredTools(tools)
                .confidence(confidence)
                .source(RoutingSource.KEYWORD)
                .reason(reason)
                .needsPlanning(needsPlanning)
                .planHint(needsPlanning ? determinePlanHint(intent) : null)
                .build();
    }

    /**
     * 根据 IntentType 确定规划提示
     */
    private String determinePlanHint(IntentType intent) {
        return switch (intent) {
            case GOAL_PLANNING -> "goal_planning";
            case RESOURCE_GENERATION -> "resource_generation";
            case PROGRESS_REVIEW -> "progress_review_with_advice";
            case TASK_MANAGEMENT -> "task_management";
            case PLAN_CONFIRMATION -> "plan_confirmation";
            default -> "general";
        };
    }

    /**
     * 将 LLM 分类结果映射为路由结果
     */
    private IntentRouteResult buildResultFromLlm(LlmIntentClassifier.LlmIntentResult llmResult, String message) {
        IntentType intent = normalizeLlmIntent(llmResult.intent(), message);
        AgentRunMode mode = selectRunMode(intent, message);
        List<String> tools = selectTools(intent, mode);
        boolean needsPlanning = mode == AgentRunMode.PLAN_AND_EXECUTE;
        String planHint = needsPlanning ? determinePlanHint(intent) : null;

        return IntentRouteResult.builder()
                .intentType(intent.getChineseName())
                .intentEnum(intent)
                .complexity("复杂")
                .runMode(mode)
                .requiredTools(tools)
                .confidence(llmResult.confidence())
                .source(RoutingSource.LLM)
                .reason("LLM语义分类: " + llmResult.reasoning())
                .needsPlanning(needsPlanning)
                .planHint(planHint)
                .build();
    }

    /**
     * LLM 分类可能把“生成某种输出格式”误判为资源生成，策略层在进入执行器前做一次语义收敛。
     */
    private IntentType normalizeLlmIntent(IntentType intent, String message) {
        if (intent == IntentType.RESOURCE_GENERATION
                && containsAny(message, DIAGRAM_KEYWORDS.toArray(new String[0]))
                && !containsAny(message, RESOURCE_GEN_KEYWORDS.toArray(new String[0]))) {
            return IntentType.DIAGRAM_GENERATION;
        }
        return intent;
    }

    /**
     * 路由策略：运行模式由意图 + 消息特征决定，而不是由单一 IntentType 硬绑定。
     */
    private AgentRunMode selectRunMode(IntentType intent, String message) {
        return switch (intent) {
            case GOAL_PLANNING, PLAN_CONFIRMATION, RESOURCE_GENERATION -> AgentRunMode.PLAN_AND_EXECUTE;
            case KNOWLEDGE_QA, PROBLEM_HELP, DIAGRAM_GENERATION, PROGRESS_REVIEW, TASK_MANAGEMENT, UNKNOWN -> AgentRunMode.REACT;
        };
    }

    /**
     * 模式选择之后再决定工具，避免格式化回答携带资源生成工具。
     * 对于 PLAN_AND_EXECUTE 模式，工具由 PlannerAgent 动态决定，这里返回空列表。
     */
    private List<String> selectTools(IntentType intent, AgentRunMode mode) {
        // PlanAndExecute 模式下工具由 PlannerAgent 动态分配，路由层不预设
        if (mode == AgentRunMode.PLAN_AND_EXECUTE) {
            return List.of();
        }

        return switch (intent) {
            case PROBLEM_HELP -> List.of("getStudentProfile", "queryKnowledge", "queryTasks");
            case PROGRESS_REVIEW -> List.of("queryLearningProgress", "getStudentProfile");
            case TASK_MANAGEMENT -> List.of("queryTasks", "manageLearningTask");
            case KNOWLEDGE_QA, DIAGRAM_GENERATION, UNKNOWN -> List.of();
            default -> List.of(); // GOAL_PLANNING, PLAN_CONFIRMATION, RESOURCE_GENERATION → PLAN_AND_EXECUTE
        };
    }

    private IntentRouteResult buildDefaultResult() {
        return IntentRouteResult.builder()
                .intentType(IntentType.KNOWLEDGE_QA.getChineseName())
                .intentEnum(IntentType.KNOWLEDGE_QA)
                .complexity("简单")
                .runMode(AgentRunMode.REACT)
                .requiredTools(List.of())
                .confidence(0.5)
                .source(RoutingSource.KEYWORD)
                .reason("未命中规则，默认使用 REACT 模式")
                .build();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
