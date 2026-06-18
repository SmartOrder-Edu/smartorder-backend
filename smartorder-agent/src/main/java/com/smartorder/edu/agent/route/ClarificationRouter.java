package com.smartorder.edu.agent.route;

import com.smartorder.edu.agent.prompt.PromptLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 澄清路由器——低置信或槽位不足时，生成追问文本引导用户补充信息
 */
@Component
@RequiredArgsConstructor
public class ClarificationRouter {

    private final PromptLoader promptLoader;

    /**
     * 各意图 + 缺失槽位组合的澄清模板映射
     */
    private static final Map<IntentType, Map<String, String>> TEMPLATES = Map.of(
            IntentType.PLAN_CONFIRMATION, Map.of(
                    "pendingPlanDraft", "PENDING_PLAN_DRAFT"
            ),
            IntentType.GOAL_PLANNING, Map.of(
                    "goalDescription", "GOAL_DESCRIPTION"
            ),
            IntentType.RESOURCE_GENERATION, Map.of(
                    "resourceTopic", "RESOURCE_TOPIC"
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
            return promptLoader.getDefaultClarification();
        }

        var intentTemplates = TEMPLATES.getOrDefault(intent, Map.of());

        // 按槽位优先级匹配：取第一个有模板的缺失槽位
        for (String slot : missingSlots) {
            String key = intentTemplates.get(slot);
            if (key != null) {
                return resolveByKey(key);
            }
        }

        return promptLoader.getDefaultClarification();
    }

    private String resolveByKey(String key) {
        return switch (key) {
            case "PENDING_PLAN_DRAFT" -> promptLoader.getPendingPlanDraftClarification();
            case "GOAL_DESCRIPTION" -> promptLoader.getGoalDescriptionClarification();
            case "RESOURCE_TOPIC" -> promptLoader.getResourceTopicClarification();
            case "PROFILE_CLARIFICATION" -> promptLoader.getProfileClarification();
            default -> promptLoader.getDefaultClarification();
        };
    }
}
