package com.smartorder.edu.agent.rag.evaluation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.rag.config.RagConfig;
import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RagEvaluationResult;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认 RAG 评估器
 * 使用 LLM 进行 RAG 三维度评估：上下文相关性、可信度、答案相关性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRagEvaluator implements RagEvaluator {

    private final ChatModel chatModel;
    private final RagConfig ragConfig;

    private static final String EVALUATION_PROMPT = """
            你是一个 RAG 系统评估专家。请评估以下检索结果的质量。

            用户查询：「%s」

            检索到的上下文：
            %s

            请从以下三个维度评估（每个维度 0-1 分）：

            1. contextRelevance（上下文相关性）：检索出来的上下文内容是否与用户查询高度相关
            2. faithfulness（可信度）：如果基于这些上下文生成答案，答案是否可信
            3. answerRelevance（答案相关性）：这些上下文是否能正确回答用户的查询

            评分标准：
            - 0.8-1.0: 优秀
            - 0.6-0.8: 良好
            - 0.4-0.6: 一般
            - 0.0-0.4: 差

            请只返回 JSON，不要返回其他内容：
            {
                "contextRelevance": 0.0,
                "faithfulness": 0.0,
                "answerRelevance": 0.0,
                "reason": "评估原因"
            }
            """;

    @Override
    public RagEvaluationResult evaluate(String query, List<RagChunk> chunks) {
        if (!ragConfig.getEvaluation().isEnabled()) {
            return buildDefaultResult();
        }

        if (chunks == null || chunks.isEmpty()) {
            return buildEmptyResult();
        }

        try {
            return llmEvaluate(query, chunks);
        } catch (Exception e) {
            log.warn("[DefaultRagEvaluator] LLM 评估失败，使用默认结果: {}", e.getMessage());
            return buildDefaultResult();
        }
    }

    /**
     * LLM 评估
     */
    private RagEvaluationResult llmEvaluate(String query, List<RagChunk> chunks) {
        // 构建上下文文本
        String contextText = buildContextText(chunks);
        String prompt = String.format(EVALUATION_PROMPT, query, contextText);

        String response = chatModel.chat(prompt);
        log.debug("[DefaultRagEvaluator] LLM 评估响应: {}", response);

        return parseResponse(response);
    }

    /**
     * 解析 LLM 响应
     */
    private RagEvaluationResult parseResponse(String response) {
        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                return buildDefaultResult();
            }

            JSONObject json = JSON.parseObject(jsonStr);

            double contextRelevance = json.getDoubleValue("contextRelevance");
            double faithfulness = json.getDoubleValue("faithfulness");
            double answerRelevance = json.getDoubleValue("answerRelevance");
            String reason = json.getString("reason");

            RagEvaluationResult result = RagEvaluationResult.builder()
                    .contextRelevance(clamp(contextRelevance))
                    .faithfulness(clamp(faithfulness))
                    .answerRelevance(clamp(answerRelevance))
                    .reason(reason)
                    .build();

            result.calculateOverall();
            return result;

        } catch (Exception e) {
            log.error("[DefaultRagEvaluator] 解析评估响应失败", e);
            return buildDefaultResult();
        }
    }

    /**
     * 构建上下文文本
     */
    private String buildContextText(List<RagChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (RagChunk chunk : chunks) {
            sb.append("[").append(index++).append("] ");
            if (chunk.getSectionTitle() != null) {
                sb.append("章节: ").append(chunk.getSectionTitle()).append("；");
            }
            sb.append("内容: ");
            // 截断过长内容
            String content = chunk.getContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // 尝试提取 ```json ... ``` 代码块
        Pattern jsonCodeBlock = Pattern.compile("```json\\s*\\n?([\\s\\S]*?)\\n?\\s*```");
        Matcher matcher = jsonCodeBlock.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 尝试提取 JSON 对象
        Pattern jsonObject = Pattern.compile("\\{[\\s\\S]*\\}");
        matcher = jsonObject.matcher(response);
        if (matcher.find()) {
            return matcher.group().trim();
        }

        return response.trim();
    }

    /**
     * 构建默认结果（评估失败时使用）
     */
    private RagEvaluationResult buildDefaultResult() {
        RagEvaluationResult result = RagEvaluationResult.builder()
                .contextRelevance(0.7)
                .faithfulness(0.7)
                .answerRelevance(0.7)
                .reason("评估不可用，使用默认分数")
                .build();
        result.calculateOverall();
        return result;
    }

    /**
     * 构建空结果（无检索结果时使用）
     */
    private RagEvaluationResult buildEmptyResult() {
        RagEvaluationResult result = RagEvaluationResult.builder()
                .contextRelevance(0.0)
                .faithfulness(0.0)
                .answerRelevance(0.0)
                .reason("无检索结果")
                .build();
        result.calculateOverall();
        return result;
    }

    /**
     * 限制值在 0-1 范围内
     */
    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
