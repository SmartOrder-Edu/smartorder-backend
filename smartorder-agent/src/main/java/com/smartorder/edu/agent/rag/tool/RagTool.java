package com.smartorder.edu.agent.rag.tool;

import com.smartorder.edu.agent.rag.model.RagQueryRequest;
import com.smartorder.edu.agent.rag.model.RagQueryResponse;
import com.smartorder.edu.agent.rag.orchestrator.RagOrchestrator;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索工具
 * 允许 Agent 在系统绑定的引用资料范围内进行知识库检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool implements ToolHandler {

    private final RagOrchestrator ragOrchestrator;
    private final TraceEventDispatcher dispatcher;

    @Override
    public String name() {
        return "ragRetrieve";
    }

    @Override
    public String description() {
        return "从用户当前已选择的知识库或文件中检索相关信息。"
                + "检索范围由系统工具层自动绑定，模型调用时只需要提供检索查询。"
                + "当用户询问已选资料、题单、文档、知识库中的内容，或需要基于这些资料回答问题时使用。"
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

        // 始终使用后端运行上下文中绑定的引用范围，不接受模型指定范围。
        List<Long> knowledgeBaseIds = context.getRuntimeContext().getKnowledgeBaseIds();
        List<Long> fileIds = context.getRuntimeContext().getFileIds();

        log.info("[RagTool] 开始执行，query={}, fileIds={}, knowledgeBaseIds={}, mode={}",
                req.getQuery(), fileIds, knowledgeBaseIds, req.getRetrievalMode());

        // 如果仍然没有绑定引用范围，返回提示信息。
        if ((knowledgeBaseIds == null || knowledgeBaseIds.isEmpty())
                && (fileIds == null || fileIds.isEmpty())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("errorMessage", "当前未选择可检索的引用资料，请先在界面选择知识库或文件");
            return result;
        }

        // 构建 RAG 查询请求
        RagQueryRequest ragRequest = RagQueryRequest.builder()
                .userId(context.getUserId())
                .query(req.getQuery() != null ? req.getQuery() : context.getRuntimeContext().getMessage())
                .fileIds(fileIds)
                .knowledgeBaseIds(knowledgeBaseIds)
                .topK(req.getTopK())
                .retrievalMode(req.getRetrievalMode())
                .build();

        // 执行 RAG 检索
        RagQueryResponse ragResponse = ragOrchestrator.retrieve(ragRequest);

        // 通过SSE推送RAG chunks给前端
        if (ragResponse.isSuccess() && context.getEmitter() != null && context.getRuntimeContext() != null) {
            dispatcher.emit(
                    context.getEmitter(),
                    context.getRuntimeContext(),
                    AgentEventType.RAG_CHUNKS,
                    Map.of(
                            "chunks", ragResponse.getChunks() != null ? ragResponse.getChunks() : List.of(),
                            "chunkCount", ragResponse.getChunks() != null ? ragResponse.getChunks().size() : 0,
                            "usedRetrievalMode", ragResponse.getUsedRetrievalMode() != null ? ragResponse.getUsedRetrievalMode() : "unknown",
                            "degraded", ragResponse.isDegraded()
                    )
            );
        }

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

        @dev.langchain4j.model.output.structured.Description("返回结果数量，默认10，最大20")
        private Integer topK;

        @dev.langchain4j.model.output.structured.Description("检索模式：auto（自动判断）、simple（简单检索）、medium（中等检索）、complex（复杂图RAG检索）。默认auto")
        private String retrievalMode;
    }
}
