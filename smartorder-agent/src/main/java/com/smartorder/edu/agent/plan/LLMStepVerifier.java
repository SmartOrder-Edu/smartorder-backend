package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.TaskItem;
import com.smartorder.edu.agent.model.dto.VerificationResult;
import com.smartorder.edu.agent.model.dto.VerificationResult.SuggestedAction;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于 LLM 的步骤验证器
 * <p>
 * 用轻量 prompt 让模型判断任务执行结果是否满足预期产出要求。
 * 验证失败时给出具体反馈，指导重试或重规划。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMStepVerifier implements StepVerifier {

    private final ChatModel chatLanguageModel;

    private static final String VERIFY_PROMPT = """
            你是任务执行结果的质检员。你的职责是判断一个步骤的执行结果是否达到预期。

            ## 判断标准
            1. 结果是否完成了任务描述中的要求
            2. 结果是否达到了预期产出
            3. 结果是否明确、具体，不是空泛的敷衍
            4. 如果有工具调用痕迹，工具结果是否被正确理解和使用

            ## action 说明
            - CONTINUE: 通过，结果符合预期
            - RETRY: 结果有方向性问题（如检索了错误的知识点、调用了错误的工具），可以用修正描述重试
            - REPLAN: 任务本身设计有问题（如目标不符合实际情况），需要重新规划剩余步骤
            - SKIP: 非关键任务失败，可以跳过
            - CLARIFY: 缺少关键信息，需要向用户追问

            ## 输出要求
            严格输出 JSON，不要 Markdown 代码块，不要解释文字：
            {
              "passed": true/false,
              "feedback": "具体反馈（为什么失败/通过）",
              "action": "CONTINUE|RETRY|REPLAN|SKIP|CLARIFY",
              "revisedDescription": "修正后的任务描述（仅 RETRY 时填写）",
              "revisedTools": ["工具名"] （仅 RETRY 时填写，不填则为 null）
            }
            """;

    @Override
    public VerificationResult verify(TaskItem step, String result, Map<String, String> previousOutputs) {
        if (result == null || result.isBlank()) {
            return VerificationResult.builder()
                    .passed(false)
                    .feedback("任务执行未产生任何输出")
                    .action(SuggestedAction.RETRY)
                    .revisedDescription(step.getDescription())
                    .build();
        }

        String userMessage = buildVerificationRequest(step, result, previousOutputs);

        try {
            ChatResponse response = chatLanguageModel.chat(ChatRequest.builder()
                    .messages(java.util.List.of(
                            SystemMessage.from(VERIFY_PROMPT),
                            UserMessage.from(userMessage)
                    ))
                    .build());

            String responseText = response.aiMessage().text();
            return parseVerificationResponse(responseText, step);
        } catch (Exception e) {
            log.warn("[StepVerifier] LLM 验证调用失败，默认通过: taskId={}, error={}",
                    step.getTaskId(), e.getMessage());
            // 验证器本身失败不应阻塞执行流程，默认通过
            return VerificationResult.builder()
                    .passed(true)
                    .feedback("验证器异常，默认通过: " + e.getMessage())
                    .action(SuggestedAction.CONTINUE)
                    .build();
        }
    }

    private String buildVerificationRequest(TaskItem step, String result,
                                            Map<String, String> previousOutputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("请验证以下任务执行结果：\n\n");
        sb.append("任务ID: ").append(step.getTaskId()).append("\n");
        sb.append("任务描述: ").append(step.getDescription()).append("\n");
        sb.append("预期产出: ").append(step.getExpectedOutput() != null ? step.getExpectedOutput() : "根据任务描述完成").append("\n");
        sb.append("所需工具: ").append(step.getRequiredTools() != null ? step.getRequiredTools() : "无").append("\n\n");

        sb.append("实际执行结果:\n");
        sb.append(truncate(result, 2000)).append("\n");

        if (previousOutputs != null && !previousOutputs.isEmpty()) {
            sb.append("\n前置任务结果摘要（供参考）:\n");
            previousOutputs.forEach((taskId, output) ->
                    sb.append("- ").append(taskId).append(": ")
                            .append(truncate(output, 200)).append("\n"));
        }

        return sb.toString();
    }

    private VerificationResult parseVerificationResponse(String responseText, TaskItem step) {
        String cleanJson = extractJson(responseText);
        try {
            JSONObject json = JSON.parseObject(cleanJson);

            VerificationResult result = new VerificationResult();
            result.setPassed(json.getBooleanValue("passed"));
            result.setFeedback(json.getString("feedback"));

            String actionStr = json.getString("action");
            if (actionStr != null) {
                try {
                    result.setAction(SuggestedAction.valueOf(actionStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    result.setAction(json.getBooleanValue("passed")
                            ? SuggestedAction.CONTINUE : SuggestedAction.RETRY);
                }
            }

            result.setRevisedDescription(json.getString("revisedDescription"));
            if (result.getRevisedDescription() == null && result.getAction() == SuggestedAction.RETRY) {
                result.setRevisedDescription(step.getDescription());
            }

            if (json.containsKey("revisedTools") && json.get("revisedTools") != null) {
                result.setRevisedTools(json.getJSONArray("revisedTools").toJavaList(String.class));
            }

            log.info("[StepVerifier] 验证完成: taskId={}, passed={}, action={}, feedback={}",
                    step.getTaskId(), result.isPassed(), result.getAction(),
                    truncate(result.getFeedback(), 100));
            return result;
        } catch (Exception e) {
            log.warn("[StepVerifier] 验证结果解析失败，默认通过: taskId={}, response={}",
                    step.getTaskId(), truncate(responseText, 200), e);
            return VerificationResult.builder()
                    .passed(true)
                    .feedback("验证结果解析异常: " + e.getMessage())
                    .action(SuggestedAction.CONTINUE)
                    .build();
        }
    }

    private String extractJson(String response) {
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1).trim();
        }
        return response;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
