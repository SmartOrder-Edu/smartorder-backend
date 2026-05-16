package com.smartorder.edu.agent.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图路由服务——第一阶段采用纯规则路由
 * 后续阶段加入小模型兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteService {

    /**
     * 根据用户消息进行意图路由
     */
    public IntentRouteResult route(String message) {
        IntentRouteResult ruleResult = routeByRule(message);
        if (ruleResult != null) {
            log.info("[IntentRoute] 规则命中: intent={}, mode={}", ruleResult.getIntentType(), ruleResult.getRunMode().getCode());
            return ruleResult;
        }

        // 第一阶段：规则未命中 → 默认 REACT 简单知识问答
        log.info("[IntentRoute] 规则未命中，使用默认 REACT 模式");
        return IntentRouteResult.builder()
                .intentType("知识问答")
                .complexity("简单")
                .runMode(AgentRunMode.REACT)
                .requiredTools(List.of())
                .reason("未命中规则，默认使用 REACT 模式")
                .build();
    }

    // 确认创建计划的关键词
    private static final List<String> CONFIRM_KEYWORDS = List.of(
            "好的", "可以", "是的", "行", "确认", "需要", "嗯", "对", "好",
            "创建吧", "开始吧", "没问题", "ok", "yes", "OK", "Yes", "搞吧", "做吧", "弄吧"
    );

    // 学习目标相关关键词
    private static final List<String> GOAL_KEYWORDS = List.of(
            "制定", "计划", "规划", "备考", "学习路径",
            "想学", "学习", "准备", "考研", "成为", "提升", "达到", "目标"
    );

    private IntentRouteResult routeByRule(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        // 用户确认创建计划 → 直接进入 PLAN_EXECUTE 模式
        // 只匹配整句基本为确认词的短消息，避免误匹配
        if (isConfirmMessage(message)) {
            return IntentRouteResult.builder()
                    .intentType("确认创建计划")
                    .complexity("复杂")
                    .runMode(AgentRunMode.PLAN_EXECUTE)
                    .requiredTools(List.of("getStudentProfile"))
                    .reason("用户确认创建学习计划，进入规划生成流程")
                    .build();
        }

        // 学习目标表达 → REACT 模式，让 AI 了解学生后主动询问是否需要创建计划
        if (containsAny(message, GOAL_KEYWORDS.toArray(new String[0]))) {
            return IntentRouteResult.builder()
                    .intentType("学习目标讨论")
                    .complexity("复杂")
                    .runMode(AgentRunMode.REACT)
                    .requiredTools(List.of("getStudentProfile", "manageLearningTask"))
                    .reason("用户表达了学习相关意图，AI 应了解学生情况后主动询问是否需要创建学习计划")
                    .build();
        }

        // 资源生成类
        if (containsAny(message, "生成资料", "复习资料", "PPT", "测试题", "全套资料")) {
            return IntentRouteResult.builder()
                    .intentType("资源生成")
                    .complexity("复杂")
                    .runMode(AgentRunMode.MULTI_AGENT)
                    .requiredTools(List.of("getStudentProfile", "queryKnowledge", "generateResource", "generateQuiz"))
                    .reason("用户需要复杂资源生成")
                    .build();
        }

        // 习题答疑类
        if (containsAny(message, "不理解", "不会", "讲一下", "为什么", "怎么做")) {
            return IntentRouteResult.builder()
                    .intentType("习题答疑")
                    .complexity("复杂")
                    .runMode(AgentRunMode.REACT)
                    .requiredTools(List.of("getStudentProfile", "queryKnowledge", "queryTasks"))
                    .reason("用户需要个性化学习辅导")
                    .build();
        }

        // 简单知识问答
        if (message.length() <= 30 && containsAny(message, "什么是", "解释", "定义")) {
            return IntentRouteResult.builder()
                    .intentType("知识问答")
                    .complexity("简单")
                    .runMode(AgentRunMode.REACT)
                    .requiredTools(List.of())
                    .reason("简单知识解释")
                    .build();
        }

        return null;
    }

    /**
     * 判断消息是否为确认创建计划的短消息
     * 只匹配消息体基本由确认词构成的短消息（<=10字符），避免长消息误匹配
     */
    private boolean isConfirmMessage(String message) {
        if (message.length() > 10) return false;
        String trimmed = message.trim();
        return CONFIRM_KEYWORDS.contains(trimmed);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
