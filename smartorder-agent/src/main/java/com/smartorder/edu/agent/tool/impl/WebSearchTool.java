package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.TracingToolExecutor;
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
                + "当你遇到以下情况时，必须使用此工具：\n"
                + "1. 时效性问题（用户提到「最新」「最近」「当前」「今天」「现在」「今年」等）\n"
                + "2. 需要实时数据（天气、股票、新闻、赛事、考试时间等）\n"
                + "3. 你对事实不确定，需要验证时\n"
                + "4. 需要比较或推荐，且涉及可能变化的信息\n"
                + "5. 用户明确要求查阅资料或引用来源\n"
                + "不要搜索的情况：数学计算、代码语法、通用常识、闲聊问候、已有确定知识的概念解释";
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

        log.info("[WebSearch] userId={}, query={}, thought={}, maxResults={}",
                context.getUserId(), query, req.getThought(), req.getMaxResults());

        // 构建带参数的搜索请求
        dev.langchain4j.web.search.WebSearchRequest.Builder reqBuilder =
                dev.langchain4j.web.search.WebSearchRequest.builder().searchTerms(query);
        if (req.getMaxResults() != null) {
            reqBuilder.maxResults(Math.max(1, Math.min(10, req.getMaxResults())));
        }
        WebSearchResults results = tavilySearchEngine.search(reqBuilder.build());

        List<Map<String, String>> referenceList = new ArrayList<>();
        StringBuilder llmContext = new StringBuilder();

        if (results != null && results.results() != null) {
            llmContext.append("【网络搜索结果】：\n");
            int index = 1;
            for (WebSearchOrganicResult result : results.results()) {
                String urlStr = result.url() != null ? result.url().toString() : "";
                Map<String, String> ref = new LinkedHashMap<>();
                ref.put("citationIndex", String.valueOf(index));
                ref.put("title", result.title());
                ref.put("url", urlStr);
                ref.put("snippet", result.snippet());
                referenceList.add(ref);

                llmContext.append(String.format("[%d] 标题：%s\n链接：%s\n内容摘要：%s\n\n",
                        index++, result.title(), urlStr, result.snippet()));
            }
        }

        if (referenceList.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("llmContext", "抱歉，未能搜索到与「" + query + "」相关的信息。请尝试更换关键词。");
            emptyResult.put("references", Collections.emptyList());
            return emptyResult;
        }

        // 将 references 存入 context attributes，供 TracingToolExecutor 传递给前端
        if (context.getAttributes() == null) {
            context.setAttributes(new LinkedHashMap<>());
        }
        context.getAttributes().put("webSearchReferences", referenceList);

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

        @dev.langchain4j.model.output.structured.Description("最大返回结果数，默认5，范围1-10。信息验证类用3，深度研究类用8-10")
        private Integer maxResults;
    }
}
