package com.smartorder.edu.agent.route;

import com.alibaba.fastjson.JSONObject;

import java.util.List;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LLM 意图分类器——规则未命中时的语义兜底
 * <p>
 * 仅做分类，不调用工具、不生成回答、不流式输出。
 * 调用一次轻量的 ChatModel，输出固定 JSON schema 的分类结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmIntentClassifier {

    private final ChatModel chatLanguageModel;

    @Value("${agent.route.llm-classifier.enabled:true}")
    private boolean enabled;

    private static final double LLM_CONFIDENCE_THRESHOLD = 0.70;

    private static final String CLASSIFY_PROMPT = """
            你是一个用户意图分类器。分析用户消息，从以下意图中选择最匹配的一个：

            - KNOWLEDGE_QA：简单事实性或定义性问题（如"什么是死锁？"）
            - PROBLEM_HELP：习题解答、概念讲解、不理解某个知识点
            - DIAGRAM_GENERATION：要求用 Mermaid、流程图、架构图、时序图、类图等格式输出技术说明
            - GOAL_PLANNING：明确请求制定学习计划或学习路径
            - RESOURCE_GENERATION：请求生成完整学习资料或学习产物（PPT、测试题、讲义、复习资料、笔记等）
            - PROGRESS_REVIEW：询问学习进度、学习情况、学习分析
            - TASK_MANAGEMENT：管理学习任务、查看今日任务、标记完成
            - UNKNOWN：以上都不匹配，或消息模糊无法确定

            请严格返回以下 JSON 格式（不要输出其他内容）：
            {"intent":"<选中的意图>","confidence":<0.0到1.0之间的数字>,"reasoning":"<简短的中文理由>"}

            规则：
            - 只选一个意图
            - confidence 表示你的确定程度
            - Mermaid、流程图、技术图、架构图、时序图如果只是指定回答格式或解释流程，归为 DIAGRAM_GENERATION，不归为 RESOURCE_GENERATION
            - 只有生成 PPT、试题、讲义、复习资料、整套学习资料等学习资源时，才归为 RESOURCE_GENERATION
            - 如果消息非常模糊，用 UNKNOWN 且 confidence < 0.5
            - 只返回 JSON，不要解释""";

    /**
     * LLM 分类结果
     */
    public record LlmIntentResult(IntentType intent, double confidence, String reasoning) {
    }

    /**
     * 对用户消息进行意图分类
     *
     * @param message 用户消息
     * @return 分类结果（绝不会返回 null，异常时返回 UNKNOWN）
     */
    public LlmIntentResult classify(String message) {
        if (!enabled) {
            log.debug("[LlmIntent] LLM分类器已禁用");
            return new LlmIntentResult(IntentType.UNKNOWN, 0.0, "LLM分类器已禁用");
        }

        if (message == null || message.isBlank()) {
            return new LlmIntentResult(IntentType.UNKNOWN, 0.0, "空消息");
        }

        try {
            ChatResponse response = chatLanguageModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(CLASSIFY_PROMPT),
                            UserMessage.from(message)
                    ))
                    .build());

            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) {
                log.warn("[LlmIntent] LLM返回空文本");
                return new LlmIntentResult(IntentType.UNKNOWN, 0.0, "LLM返回空文本");
            }

            // 提取 JSON（LLM 可能在 JSON 前后有额外的 markdown 标记）
            String json = extractJson(text);
            JSONObject obj = JSONObject.parseObject(json);

            String intentStr = obj.getString("intent");
            IntentType intent;
            try {
                intent = IntentType.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                log.warn("[LlmIntent] 未知意图标签: {}, fallback到UNKNOWN", intentStr);
                intent = IntentType.UNKNOWN;
            }

            double confidence = obj.getDoubleValue("confidence");
            // 归一化置信度
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            String reasoning = obj.getString("reasoning");

            log.info("[LlmIntent] LLM分类: intent={}, confidence={}, reason={}", intent, confidence, reasoning);
            return new LlmIntentResult(intent, confidence, reasoning);

        } catch (Exception e) {
            log.warn("[LlmIntent] LLM分类异常，返回UNKNOWN: {}", e.getMessage());
            return new LlmIntentResult(IntentType.UNKNOWN, 0.0, "LLM分类异常: " + e.getMessage());
        }
    }

    /**
     * 判断 LLM 结果的置信度是否达到阈值
     */
    public boolean isConfident(LlmIntentResult result) {
        return result.intent() != IntentType.UNKNOWN && result.confidence() >= LLM_CONFIDENCE_THRESHOLD;
    }

    /**
     * 从 LLM 返回文本中提取 JSON 字符串
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        // 处理 ```json ... ``` 包裹的情况
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("{");
            int end = trimmed.lastIndexOf("}");
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        // 处理直接 JSON
        int start = trimmed.indexOf("{");
        int end = trimmed.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
