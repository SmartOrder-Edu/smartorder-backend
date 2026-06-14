package com.smartorder.edu.agent.rag.tool;

import com.smartorder.edu.agent.rag.model.RagQueryRequest;
import com.smartorder.edu.agent.rag.model.RagQueryResponse;
import com.smartorder.edu.agent.rag.orchestrator.RagOrchestrator;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索工具
 * 允许 Agent 进行知识库检索，支持文件ID和知识库ID指定检索范围
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool implements ToolHandler {

    private final RagOrchestrator ragOrchestrator;

    @Override
    public String name() {
        return "ragRetrieve";
    }

    @Override
    public String description() {
        return "从知识库中检索相关信息。支持指定文件ID集合和知识库ID集合进行精确检索。"
                + "当用户询问知识库中的内容、文档内容、或需要基于知识库回答问题时使用。"
                + "检索模式可选：auto（自动判断）、simple（简单检索）、medium（中等检索）、complex（复杂图RAG检索）。";
    }

    @Override
    public Class<?> requestType() {
        return RagRetrieveRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SEARCH;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        RagRetrieveRequest req = (RagRetrieveRequest) request;
        log.info("[RagTool] 开始执行，query={}, fileIds={}, knowledgeBaseIds={}, mode={}",
                req.getQuery(), req.getFileIds(), req.getKnowledgeBaseIds(), req.getRetrievalMode());

        // 构建 RAG 查询请求
        RagQueryRequest ragRequest = RagQueryRequest.builder()
                .userId(context.getUserId())
                .query(req.getQuery() != null ? req.getQuery() : context.getRuntimeContext().getMessage())
                .fileIds(req.getFileIds())
                .knowledgeBaseIds(req.getKnowledgeBaseIds())
                .topK(req.getTopK())
                .retrievalMode(req.getRetrievalMode())
                .build();

        // 执行 RAG 检索
        RagQueryResponse ragResponse = ragOrchestrator.retrieve(ragRequest);

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ragResponse.isSuccess());

        if (ragResponse.isSuccess()) {
            result.put("contextText", ragResponse.getContextText());
            result.put("usedRetrievalMode", ragResponse.getUsedRetrievalMode());
            result.put("chunkCount", ragResponse.getChunks() != null ? ragResponse.getChunks().size() : 0);
            result.put("degraded", ragResponse.isDegraded());

            if (ragResponse.getEvaluationResult() != null) {
                result.put("evaluationScore", ragResponse.getEvaluationResult().getOverallScore());
                result.put("evaluationPassed", ragResponse.getEvaluationResult().isPassed());
            }
        } else {
            result.put("errorMessage", ragResponse.getErrorMessage());
        }

        log.info("[RagTool] 执行完成，success={}, chunkCount={}",
                result.get("success"), result.get("chunkCount"));

        return result;
    }

    /**
     * RAG 检索请求
     */
    @Data
    public static class RagRetrieveRequest {
        @dev.langchain4j.model.output.structured.Description("检索查询语句，应结合用户问题改写为清晰的检索查询")
        private String query;

        @dev.langchain4j.model.output.structured.Description("文件ID列表，指定要检索的文件。可省略")
        private List<Long> fileIds;

        @dev.langchain4j.model.output.structured.Description("知识库ID列表，指定要检索的知识库。可省略")
        private List<Long> knowledgeBaseIds;

        @dev.langchain4j.model.output.structured.Description("返回结果数量，默认10，最大20")
        private Integer topK;

        @dev.langchain4j.model.output.structured.Description("检索模式：auto（自动判断）、simple（简单检索）、medium（中等检索）、complex（复杂图RAG检索）。默认auto")
        private String retrievalMode;
    }
}
