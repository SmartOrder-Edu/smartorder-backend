package com.smartorder.edu.agent.rag.analysis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.rag.config.RagConfig;
import com.smartorder.edu.agent.rag.model.QueryAnalysisResult;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 查询分析器
 * 使用大语言模型进行四维分析：复杂度、关系、推理、实体
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmQueryAnalyzer implements QueryAnalyzer {

    private final ChatModel chatModel;
    private final RagConfig ragConfig;

    private static final String ANALYSIS_PROMPT = """
            你是一个查询分析专家。请分析以下用户查询，并返回 JSON 格式的分析结果。

            用户查询：「%s」

            请从以下四个维度分析：
            1. complexity（复杂度 0-1）：查询的复杂程度，0表示简单事实查询，1表示需要复杂推理
            2. relationScore（关系强度 0-1）：是否需要查询实体间的关系，0表示无关系需求，1表示需要深度关系推理
            3. reasoningScore（推理深度 0-1）：是否需要多步推理，0表示直接检索即可，1表示需要多步推理
            4. entities（实体列表）：查询中涉及的关键实体（知识点、概念、名词等）

            判断规则：
            - 简单查询（complexity < 0.3）：直接事实查询，如"什么是XXX"、"XXX的定义"
            - 中等查询（0.3 <= complexity < 0.7）：需要一定关联，如"XXX和YYY的关系"、"XXX的影响"
            - 复杂查询（complexity >= 0.7）：需要多跳推理，如"为什么XXX会导致YYY"、"分析XXX的发展趋势"

            请只返回 JSON，不要返回其他内容：
            {
                "complexity": 0.0,
                "relationScore": 0.0,
                "reasoningScore": 0.0,
                "entities": ["实体1", "实体2"],
                "reason": "分析原因"
            }
            """;

    @Override
    public QueryAnalysisResult analyze(String query) {
        if (!ragConfig.getAnalysis().isLlmEnabled()) {
            return ruleBasedAnalyze(query);
        }

        try {
            return llmAnalyze(query);
        } catch (Exception e) {
            log.warn("[LlmQueryAnalyzer] LLM 分析失败，降级为规则分析: {}", e.getMessage());
            return ruleBasedAnalyze(query);
        }
    }

    /**
     * LLM 分析
     */
    private QueryAnalysisResult llmAnalyze(String query) {
        String prompt = String.format(ANALYSIS_PROMPT, query);
        String response = chatModel.chat(prompt);

        log.debug("[LlmQueryAnalyzer] LLM 响应: {}", response);

        return parseResponse(response, query);
    }

    /**
     * 解析 LLM 响应
     */
    private QueryAnalysisResult parseResponse(String response, String query) {
        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("[LlmQueryAnalyzer] 无法提取 JSON，使用规则分析");
                return ruleBasedAnalyze(query);
            }

            JSONObject json = JSON.parseObject(jsonStr);

            double complexity = json.getDoubleValue("complexity");
            double relationScore = json.getDoubleValue("relationScore");
            double reasoningScore = json.getDoubleValue("reasoningScore");
            String reason = json.getString("reason");

            List<String> entities = new ArrayList<>();
            JSONArray entitiesArray = json.getJSONArray("entities");
            if (entitiesArray != null) {
                for (int i = 0; i < entitiesArray.size(); i++) {
                    entities.add(entitiesArray.getString(i));
                }
            }

            String suggestedMode = suggestMode(complexity, relationScore, reasoningScore);

            return QueryAnalysisResult.builder()
                    .complexity(clamp(complexity))
                    .relationScore(clamp(relationScore))
                    .reasoningScore(clamp(reasoningScore))
                    .entities(entities)
                    .suggestedMode(suggestedMode)
                    .confidence(0.85)
                    .reason(reason)
                    .build();

        } catch (Exception e) {
            log.error("[LlmQueryAnalyzer] 解析响应失败", e);
            return ruleBasedAnalyze(query);
        }
    }

    /**
     * 规则分析（降级方案）
     */
    private QueryAnalysisResult ruleBasedAnalyze(String query) {
        double complexity = 0.0;
        double relationScore = 0.0;
        double reasoningScore = 0.0;
        List<String> entities = new ArrayList<>();

        String lowerQuery = query.toLowerCase();

        // 复杂度分析
        if (containsAny(lowerQuery, "为什么", "如何", "怎样", "分析", "比较", "对比", "评估")) {
            complexity += 0.4;
        }
        if (containsAny(lowerQuery, "趋势", "影响", "发展", "演变", "因果")) {
            complexity += 0.3;
        }
        if (containsAny(lowerQuery, "是什么", "定义", "含义", "意思")) {
            complexity -= 0.2;
        }

        // 关系分析
        if (containsAny(lowerQuery, "关系", "联系", "关联", "之间", "和", "与")) {
            relationScore += 0.5;
        }
        if (containsAny(lowerQuery, "区别", "异同", "对比")) {
            relationScore += 0.3;
        }

        // 推理分析
        if (containsAny(lowerQuery, "为什么", "原因", "导致", "因此", "所以")) {
            reasoningScore += 0.5;
        }
        if (containsAny(lowerQuery, "如果", "假设", "可能", "推测")) {
            reasoningScore += 0.3;
        }

        // 实体提取（简单规则：提取引号中的内容和关键名词）
        Pattern quotedPattern = Pattern.compile("[\"「『](.*?)[\"」』]");
        Matcher matcher = quotedPattern.matcher(query);
        while (matcher.find()) {
            entities.add(matcher.group(1));
        }

        // 限制范围
        complexity = clamp(complexity);
        relationScore = clamp(relationScore);
        reasoningScore = clamp(reasoningScore);

        String suggestedMode = suggestMode(complexity, relationScore, reasoningScore);

        return QueryAnalysisResult.builder()
                .complexity(complexity)
                .relationScore(relationScore)
                .reasoningScore(reasoningScore)
                .entities(entities)
                .suggestedMode(suggestedMode)
                .confidence(0.6)
                .reason("规则分析（LLM 分析不可用）")
                .build();
    }

    /**
     * 建议检索模式
     */
    private String suggestMode(double complexity, double relationScore, double reasoningScore) {
        double maxScore = Math.max(complexity, Math.max(relationScore, reasoningScore));

        if (maxScore >= ragConfig.getAnalysis().getComplexThreshold()) {
            return "complex";
        } else if (maxScore >= ragConfig.getAnalysis().getSimpleThreshold()) {
            return "medium";
        } else {
            return "simple";
        }
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
     * 检查是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 限制值在 0-1 范围内
     */
    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
