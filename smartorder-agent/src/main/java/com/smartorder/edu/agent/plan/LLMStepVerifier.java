package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.model.dto.VerificationResult;
import com.smartorder.edu.agent.model.dto.VerificationResult.SuggestedAction;
import com.smartorder.edu.agent.prompt.PromptLoader;
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
 * 基于 LLM 的步骤验证器。
 * <p>
 * 用轻量 prompt 让模型判断执行步骤的结果是否满足预期产出要求。
 * 验证失败时给出具体反馈，指导重试或重规划。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMStepVerifier implements StepVerifier {

    private final ChatModel chatLanguageModel;
    private final PromptLoader promptLoader;

    @Override
    public VerificationResult verify(ExecutionStep step, String result, Map<String, String> previousOutputs) {
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
                            SystemMessage.from(promptLoader.getStepVerifierPrompt()),
                            UserMessage.from(userMessage)
                    ))
                    .build());

            String responseText = response.aiMessage().text();
            return parseVerificationResponse(responseText, step);
        } catch (Exception e) {
            log.warn("[StepVerifier] LLM 验证调用失败，默认通过: taskId={}, error={}",
                    step.getStepId(), e.getMessage());
            // 验证器本身失败不应阻塞执行流程，默认通过
            return VerificationResult.builder()
                    .passed(true)
                    .feedback("验证器异常，默认通过: " + e.getMessage())
                    .action(SuggestedAction.CONTINUE)
                    .build();
        }
    }

    private String buildVerificationRequest(ExecutionStep step, String result,
                                            Map<String, String> previousOutputs) {
        StringBuilder prevSb = new StringBuilder();
        if (previousOutputs != null && !previousOutputs.isEmpty()) {
            prevSb.append("\n前置任务结果摘要（供参考）:\n");
            previousOutputs.forEach((taskId, output) ->
                    prevSb.append("- ").append(taskId).append(": ")
                            .append(truncate(output, 200)).append("\n"));
        }

        return PromptLoader.format(promptLoader.getVerificationRequestTemplate(),
                step.getStepId(),
                step.getDescription(),
                step.getExpectedOutput() != null ? step.getExpectedOutput() : "根据任务描述完成",
                step.getRequiredTools() != null ? step.getRequiredTools() : "无",
                truncate(result, 2000),
                prevSb.toString());
    }

    private VerificationResult parseVerificationResponse(String responseText, ExecutionStep step) {
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
                    step.getStepId(), result.isPassed(), result.getAction(),
                    truncate(result.getFeedback(), 100));
            return result;
        } catch (Exception e) {
            log.warn("[StepVerifier] 验证结果解析失败，默认通过: taskId={}, response={}",
                    step.getStepId(), truncate(responseText, 200), e);
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
