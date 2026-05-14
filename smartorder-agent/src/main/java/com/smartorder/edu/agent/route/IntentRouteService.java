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

    private IntentRouteResult routeByRule(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        // 目标规划类
        if (containsAny(message, "制定", "计划", "规划", "备考", "学习路径")) {
            return IntentRouteResult.builder()
                    .intentType("目标规划")
                    .complexity("复杂")
                    .runMode(AgentRunMode.PLAN_EXECUTE)
                    .requiredTools(List.of("getStudentProfile", "manageLearningTask"))
                    .reason("用户表达了明确的学习目标，需要生成规划")
                    .build();
        }

        // 资源生成类
        if (containsAny(message, "生成资料", "复习资料", "PPT", "测试题", "全套资料")) {
            return IntentRouteResult.builder()
                    .intentType("资源生成")
                    .complexity("复杂")
                    .runMode(AgentRunMode.MULTI_AGENT)
                    .requiredTools(List.of("getStudentProfile", "manageLearningTask"))
                    .reason("用户需要复杂资源生成")
                    .build();
        }

        // 习题答疑类
        if (containsAny(message, "不理解", "不会", "讲一下", "为什么", "怎么做")) {
            return IntentRouteResult.builder()
                    .intentType("习题答疑")
                    .complexity("复杂")
                    .runMode(AgentRunMode.REACT)
                    .requiredTools(List.of("getStudentProfile"))
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

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
