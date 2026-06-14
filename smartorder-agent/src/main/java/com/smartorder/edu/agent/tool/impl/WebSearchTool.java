package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchResults;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具 — 基于 Tavily API，为 Agent 提供实时网络信息检索能力
 *
 * @author 谢光湘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements ToolHandler {

    private final WebSearchEngine tavilySearchEngine;

    @Override
    public String name() {
        return "webSearch";
    }

    @Override
    public String description() {
        return "在互联网上搜索信息以获取最新实时信息。"
                + "当问题具有时效性（用户提到「最新」「最近」「当前」「今天」等）、"
                + "需要实时数据（天气、股票、新闻、事件等）、"
                + "或你无法确定事实需要验证时，优先使用此工具。";
    }

    @Override
    public Class<?> requestType() {
        return WebSearchRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SEARCH;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        WebSearchRequest req = (WebSearchRequest) request;
        String query = req.getQuery();

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词 query 不能为空");
        }

        log.info("[WebSearch] userId={}, query={}, thought={}", context.getUserId(), query, req.getThought());

        WebSearchResults results = tavilySearchEngine.search(query);

        List<Map<String, String>> referenceList = new ArrayList<>();
        StringBuilder llmContext = new StringBuilder();

        if (results != null && results.results() != null) {
            llmContext.append("【网络搜索结果】：\n");
            int index = 1;
            for (WebSearchOrganicResult result : results.results()) {
                Map<String, String> ref = new LinkedHashMap<>();
                ref.put("title", result.title());
                ref.put("url", result.url() != null ? result.url().toString() : "");
                ref.put("snippet", result.snippet());
                referenceList.add(ref);

                llmContext.append(String.format("[%d] 标题：%s\n内容摘要：%s\n\n",
                        index++, result.title(), result.snippet()));
            }
        }

        if (referenceList.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("llmContext", "抱歉，未能搜索到与「" + query + "」相关的信息。请尝试更换关键词。");
            emptyResult.put("references", Collections.emptyList());
            return emptyResult;
        }

        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("llmContext", llmContext.toString());
        finalResult.put("references", referenceList);
        return finalResult;
    }

    @Data
    public static class WebSearchRequest {
        @dev.langchain4j.model.output.structured.Description("搜索关键词，应提炼用户问题的核心关键词以提高搜索精度")
        private String query;

        @dev.langchain4j.model.output.structured.Description("【必填】调用此工具前，你对当前进度的分析以及为什么需要搜索的思考过程")
        private String thought;
    }
}
