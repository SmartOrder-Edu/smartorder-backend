package com.smartorder.edu.agent.rag.orchestrator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.agent.rag.analysis.QueryAnalyzer;
import com.smartorder.edu.agent.rag.config.RagConfig;
import com.smartorder.edu.agent.rag.evaluation.RagEvaluator;
import com.smartorder.edu.agent.rag.health.RagHealthChecker;
import com.smartorder.edu.agent.rag.model.QueryAnalysisResult;
import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RagEvaluationResult;
import com.smartorder.edu.agent.rag.model.RagQueryRequest;
import com.smartorder.edu.agent.rag.model.RagQueryResponse;
import com.smartorder.edu.agent.rag.model.RetrievalContext;
import com.smartorder.edu.agent.rag.model.RetrievalRouteResult;
import com.smartorder.edu.agent.rag.rerank.RrfReranker;
import com.smartorder.edu.agent.rag.retriever.Retriever;
import com.smartorder.edu.agent.rag.retriever.RetrieverType;
import com.smartorder.edu.agent.rag.router.RetrievalRouter;
import com.smartorder.edu.common.mapper.KnowledgeChunkMapper;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RAG 编排器
 * 负责编排整个 RAG 流程：健康检查 -> 查询分析 -> 路由 -> 检索 -> 重排 -> 评估
 */
@Slf4j
@Component
public class RagOrchestrator {

    private final RagHealthChecker healthChecker;
    private final QueryAnalyzer queryAnalyzer;
    private final RetrievalRouter retrievalRouter;
    private final Map<RetrieverType, Retriever> retrievers;
    private final RrfReranker reranker;
    private final RagEvaluator ragEvaluator;
    private final RagConfig ragConfig;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public RagOrchestrator(RagHealthChecker healthChecker,
                           QueryAnalyzer queryAnalyzer,
                           RetrievalRouter retrievalRouter,
                           List<Retriever> retrieverList,
                           RrfReranker reranker,
                           RagEvaluator ragEvaluator,
                           RagConfig ragConfig,
                           KnowledgeChunkMapper knowledgeChunkMapper) {
        this.healthChecker = healthChecker;
        this.queryAnalyzer = queryAnalyzer;
        this.retrievalRouter = retrievalRouter;
        this.reranker = reranker;
        this.ragEvaluator = ragEvaluator;
        this.ragConfig = ragConfig;
        this.knowledgeChunkMapper = knowledgeChunkMapper;

        // 构建检索器映射
        this.retrievers = new HashMap<>();
        for (Retriever retriever : retrieverList) {
            this.retrievers.put(retriever.type(), retriever);
        }
        log.info("[RagOrchestrator] 已注册检索器: {}", this.retrievers.keySet());
    }

    /**
     * 执行 RAG 检索
     *
     * @param request RAG 查询请求
     * @return RAG 查询响应
     */
    public RagQueryResponse retrieve(RagQueryRequest request) {
        log.info("[RagOrchestrator] 开始 RAG 检索，query={}, fileIds={}, knowledgeBaseIds={}, mode={}",
                request.getQuery(), request.getFileIds(), request.getKnowledgeBaseIds(),
                request.getRetrievalMode());

        // 1. 健康检查
        if (!healthChecker.isReady()) {
            log.error("[RagOrchestrator] RAG 系统未就绪");
            return RagQueryResponse.failure("RAG 系统未就绪，请检查系统配置");
        }

        // 2. 参数校验
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return RagQueryResponse.failure("查询文本不能为空");
        }

        if ((request.getFileIds() == null || request.getFileIds().isEmpty())
                && (request.getKnowledgeBaseIds() == null || request.getKnowledgeBaseIds().isEmpty())) {
            return RagQueryResponse.failure("必须指定文件ID或知识库ID");
        }

        try {
            // 3. 查询分析
            QueryAnalysisResult analysisResult = queryAnalyzer.analyze(request.getQuery());
            log.info("[RagOrchestrator] 查询分析完成: complexity={}, relationScore={}, reasoningScore={}, suggestedMode={}",
                    analysisResult.getComplexity(), analysisResult.getRelationScore(),
                    analysisResult.getReasoningScore(), analysisResult.getSuggestedMode());

            // 4. 检索路由
            RetrievalRouteResult routeResult = retrievalRouter.route(
                    analysisResult, request.getRetrievalMode());
            log.info("[RagOrchestrator] 检索路由决策: mode={}, degraded={}",
                    routeResult.getRetrievalMode(), routeResult.isDegraded());

            // 5. 构建检索上下文
            RetrievalContext context = buildRetrievalContext(request, analysisResult, routeResult);

            // 6. 执行检索（带降级）
            List<RagChunk> chunks = executeRetrievalWithDegrade(context, routeResult);

            // 7. 重排
            List<RagChunk> rerankedChunks = reranker.rerank(chunks);

            // 7.5 查询数据库获取完整分块信息
            rerankedChunks = enrichChunksWithDbInfo(rerankedChunks);

            // 8. 评估
            RagEvaluationResult evaluationResult = ragEvaluator.evaluate(
                    request.getQuery(), rerankedChunks);
            log.info("[RagOrchestrator] RAG 评估完成: overallScore={}, passed={}",
                    evaluationResult.getOverallScore(), evaluationResult.isPassed());

            // 9. 构建上下文文本
            String contextText = buildContextText(rerankedChunks);

            return RagQueryResponse.success(
                    rerankedChunks,
                    contextText,
                    routeResult.getRetrievalMode(),
                    analysisResult,
                    evaluationResult,
                    routeResult.isDegraded(),
                    routeResult.getReason()
            );

        } catch (Exception e) {
            log.error("[RagOrchestrator] RAG 检索异常", e);
            return RagQueryResponse.failure("RAG 检索失败: " + e.getMessage());
        }
    }

    /**
     * 构建检索上下文
     */
    private RetrievalContext buildRetrievalContext(RagQueryRequest request,
                                                   QueryAnalysisResult analysisResult,
                                                   RetrievalRouteResult routeResult) {
        return RetrievalContext.builder()
                .userId(request.getUserId())
                .query(request.getQuery())
                .fileIds(request.getFileIds())
                .knowledgeBaseIds(request.getKnowledgeBaseIds())
                .topK(request.getTopK() != null ? request.getTopK() : ragConfig.getDefaultTopK())
                .analysisResult(analysisResult)
                .routeResult(routeResult)
                .entities(analysisResult.getEntities())
                .build();
    }

    /**
     * 执行检索（带降级机制）
     */
    private List<RagChunk> executeRetrievalWithDegrade(RetrievalContext context,
                                                       RetrievalRouteResult routeResult) {
        String currentMode = routeResult.getRetrievalMode();
        boolean degraded = routeResult.isDegraded();
        StringBuilder degradeReason = new StringBuilder(
                routeResult.getReason() != null ? routeResult.getReason() : "");

        while (currentMode != null) {
            RetrieverType type = RetrieverType.fromCode(currentMode);
            Retriever retriever = retrievers.get(type);

            if (retriever == null) {
                log.warn("[RagOrchestrator] 未找到检索器: {}", currentMode);
                currentMode = type.degrade() != null ? type.degrade().getCode() : null;
                degraded = true;
                degradeReason.append("；未找到检索器: ").append(type);
                continue;
            }

            try {
                log.info("[RagOrchestrator] 执行检索: mode={}", currentMode);
                List<RagChunk> chunks = retriever.retrieve(context);

                if (chunks != null && !chunks.isEmpty()) {
                    log.info("[RagOrchestrator] 检索成功: mode={}, 结果数量={}",
                            currentMode, chunks.size());
                    return chunks;
                }

                log.warn("[RagOrchestrator] 检索结果为空: mode={}", currentMode);

            } catch (Exception e) {
                log.error("[RagOrchestrator] 检索失败: mode={}, error={}", currentMode, e.getMessage());
            }

            // 降级
            if (ragConfig.getDegrade().isEnabled()) {
                RetrieverType nextType = type.degrade();
                if (nextType != null) {
                    degradeReason.append("；").append(currentMode).append("检索失败，降级到").append(nextType.getCode());
                    currentMode = nextType.getCode();
                    degraded = true;
                    log.info("[RagOrchestrator] 降级到: {}", currentMode);
                } else {
                    // 已经是最低级，无法继续降级
                    log.error("[RagOrchestrator] 最低级检索仍然失败，返回空结果");
                    if (ragConfig.getDegrade().isAlertOnSimpleFailure()) {
                        // TODO: 发送告警通知
                        log.error("[RagOrchestrator] 触发告警：简单检索失败");
                    }
                    return new ArrayList<>();
                }
            } else {
                return new ArrayList<>();
            }
        }

        return new ArrayList<>();
    }

    /**
     * 构建上下文文本
     */
    private String buildContextText(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【RAG 检索结果】\n");
        sb.append("以下内容来自知识库检索，回答问题时优先依据这些内容。\n");
        sb.append("如果内容不足，请明确说明\"知识库中未找到足够依据\"。\n\n");

        int index = 1;
        for (RagChunk chunk : chunks) {
            sb.append("[").append(index++).append("] ");

            if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isBlank()) {
                sb.append("章节: ").append(chunk.getSectionTitle());
            }

            if (chunk.getPageNumber() != null) {
                sb.append("；页码: ").append(chunk.getPageNumber());
            }

            sb.append("\n");

            // 截断过长内容
            String content = chunk.getContent();
            if (content != null) {
                if (content.length() > 900) {
                    content = content.substring(0, 900) + "...";
                }
                sb.append("内容: ").append(content);
            }

            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 从数据库查询完整分块信息，补充到 RagChunk 中
     */
    private List<RagChunk> enrichChunksWithDbInfo(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 收集所有 vectorId
        List<String> vectorIds = chunks.stream()
                .map(RagChunk::getVectorId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (vectorIds.isEmpty()) {
            return chunks;
        }

        try {
            // 批量查询数据库
            List<KnowledgeChunk> dbChunks = knowledgeChunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunk>()
                            .in(KnowledgeChunk::getVectorId, vectorIds)
                            .eq(KnowledgeChunk::getStatus, "active")
            );

            // 构建 vectorId -> KnowledgeChunk 映射
            Map<String, KnowledgeChunk> dbChunkMap = dbChunks.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getVectorId, c -> c, (a, b) -> a));

            // 补充信息到 RagChunk
            for (RagChunk chunk : chunks) {
                KnowledgeChunk dbChunk = dbChunkMap.get(chunk.getVectorId());
                if (dbChunk != null) {
                    // 如果 Milvus 中的字段为空，从数据库补充
                    if (chunk.getChunkId() == null) {
                        chunk.setChunkId(dbChunk.getId());
                    }
                    if (chunk.getKnowledgeBaseId() == null) {
                        chunk.setKnowledgeBaseId(dbChunk.getKnowledgeBaseId());
                    }
                    if (chunk.getFileId() == null) {
                        chunk.setFileId(dbChunk.getFileId());
                    }
                    if (chunk.getDocumentId() == null) {
                        chunk.setDocumentId(dbChunk.getDocumentId());
                    }
                    if (chunk.getPageNumber() == null) {
                        chunk.setPageNumber(dbChunk.getPageNumber());
                    }
                    if (chunk.getSectionTitle() == null) {
                        chunk.setSectionTitle(dbChunk.getSectionTitle());
                    }
                    if (chunk.getTitlePath() == null) {
                        chunk.setTitlePath(dbChunk.getTitlePath());
                    }
                    // 如果 Milvus 中没有内容，从数据库获取
                    if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                        chunk.setContent(dbChunk.getContent());
                    }

                    // 添加额外的元数据
                    if (chunk.getMetadata() == null) {
                        chunk.setMetadata(new HashMap<>());
                    }
                    chunk.getMetadata().put("contentPreview", dbChunk.getContentPreview());
                    chunk.getMetadata().put("tokenCount", dbChunk.getTokenCount());
                    chunk.getMetadata().put("charCount", dbChunk.getCharCount());
                    chunk.getMetadata().put("chunkType", dbChunk.getChunkType());
                    chunk.getMetadata().put("sectionLevel", dbChunk.getSectionLevel());
                }
            }

            log.info("[RagOrchestrator] 数据库补充分块信息完成，查询到 {} 条记录", dbChunks.size());

        } catch (Exception e) {
            log.error("[RagOrchestrator] 查询数据库分块信息失败", e);
            // 不影响主流程，继续返回原结果
        }

        return chunks;
    }
}
