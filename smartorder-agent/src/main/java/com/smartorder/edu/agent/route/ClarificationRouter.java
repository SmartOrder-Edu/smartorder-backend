package com.smartorder.edu.agent.route;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 澄清路由器——低置信或槽位不足时，生成追问文本引导用户补充信息
 */
@Component
public class ClarificationRouter {

    /**
     * 默认澄清模板（列出所有可选操作）
     */
    private static final String DEFAULT_CLARIFICATION =
            "我不太确定你的意思，你是想：\n"
                    + "1. 解答某个知识点的问题\n"
                    + "2. 制定一个学习计划\n"
                    + "3. 生成学习资料（如PPT、测试题）\n"
                    + "请告诉我更多细节，我会尽力帮你。";

    /**
     * 各意图 + 缺失槽位组合的澄清模板
     */
    private static final Map<IntentType, Map<String, String>> TEMPLATES = Map.of(
            IntentType.PLAN_CONFIRMATION, Map.of(
                    "pendingPlanDraft",
                    "我没有找到你之前的学习规划草稿。你想制定什么样的学习计划呢？请描述一下你的目标吧。"
            ),
            IntentType.GOAL_PLANNING, Map.of(
                    "goalDescription",
                    "请多告诉我一些你的学习目标，比如你想学习什么科目、有多长时间可以利用、目标是什么（考试、项目、比赛等）？"
            ),
            IntentType.RESOURCE_GENERATION, Map.of(
                    "resourceTopic",
                    "你想让我生成关于哪个科目的学习资料呢？请告诉我具体的科目或知识点。"
            )
    );

    /**
     * 根据意图和缺失槽位生成澄清问句
     *
     * @param intent       意图类型
     * @param missingSlots 缺失槽位列表
     * @return 澄清问句文本
     */
    public String buildClarification(IntentType intent, List<String> missingSlots) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return DEFAULT_CLARIFICATION;
        }

        var intentTemplates = TEMPLATES.getOrDefault(intent, Map.of());

        // 按槽位优先级匹配：取第一个有模板的缺失槽位
        for (String slot : missingSlots) {
            String template = intentTemplates.get(slot);
            if (template != null) {
                return template;
            }
        }

        return DEFAULT_CLARIFICATION;
    }
}
