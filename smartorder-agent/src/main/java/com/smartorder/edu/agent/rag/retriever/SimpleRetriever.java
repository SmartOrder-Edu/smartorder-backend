package com.smartorder.edu.agent.rag.retriever;

import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RetrievalContext;
import com.smartorder.edu.agent.rag.rerank.ReciprocalRankFusion;
import com.smartorder.edu.common.model.rag.AttachmentRagSearchResult;
import com.smartorder.edu.common.rag.VectorIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 简单检索器
 * 向量 + 关键词混合检索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleRetriever implements Retriever {

    private final VectorIndexService vectorIndexService;

    @Override
    public RetrieverType type() {
        return RetrieverType.SIMPLE;
    }

    @Override
    public List<RagChunk> retrieve(RetrievalContext context) {
        log.info("[SimpleRetriever] 开始简单检索，query={}, fileIds={}, knowledgeBaseIds={}",
                context.getQuery(), context.getFileIds(), context.getKnowledgeBaseIds());

        boolean hasFileIds = context.getFileIds() != null && !context.getFileIds().isEmpty();
        boolean hasKnowledgeBaseIds = context.getKnowledgeBaseIds() != null && !context.getKnowledgeBaseIds().isEmpty();

        try {
            // 场景1：同时指定 fileIds 和 knowledgeBaseIds → 分别检索 + RRF 融合
            if (hasFileIds && hasKnowledgeBaseIds) {
                List<RagChunk> fileResults = retrieveByFileIds(context);
                List<RagChunk> kbResults = retrieveByKnowledgeBaseIds(context);
                return mergeResults(fileResults, kbResults, context.getTopK());
            }
            // 场景2：仅指定 fileIds
            else if (hasFileIds) {
                return retrieveByFileIds(context);
            }
            // 场景3：仅指定 knowledgeBaseIds
            else if (hasKnowledgeBaseIds) {
                return retrieveByKnowledgeBaseIds(context);
            }
            // 场景4：未指定任何范围
            else {
                log.warn("[SimpleRetriever] 未指定文件ID或知识库ID");
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("[SimpleRetriever] 简单检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 按文件ID检索
     */
    private List<RagChunk> retrieveByFileIds(RetrievalContext context) {
        log.info("[SimpleRetriever] 按文件ID检索: {}", context.getFileIds());
        List<AttachmentRagSearchResult> results = vectorIndexService.searchByFileIds(
                context.getQuery(), context.getUserId(), context.getFileIds(), context.getTopK());
        List<RagChunk> chunks = results.stream()
                .map(RagChunk::fromAttachmentResult)
                .collect(Collectors.toList());
        log.info("[SimpleRetriever] 文件检索完成，结果数量: {}", chunks.size());
        return chunks;
    }

    /**
     * 按知识库ID检索
     */
    private List<RagChunk> retrieveByKnowledgeBaseIds(RetrievalContext context) {
        log.info("[SimpleRetriever] 按知识库ID检索: {}", context.getKnowledgeBaseIds());
        List<AttachmentRagSearchResult> results = vectorIndexService.searchByKnowledgeBaseIds(
                context.getQuery(), context.getUserId(), context.getKnowledgeBaseIds(), context.getTopK());
        List<RagChunk> chunks = results.stream()
                .map(RagChunk::fromAttachmentResult)
                .collect(Collectors.toList());
        log.info("[SimpleRetriever] 知识库检索完成，结果数量: {}", chunks.size());
        return chunks;
    }

    /**
     * 合并两路检索结果（RRF 融合）
     * 每路各取 topK，合并后截取 topK
     */
    private List<RagChunk> mergeResults(List<RagChunk> fileResults, List<RagChunk> kbResults, int topK) {
        // 如果其中一路为空，直接返回另一路
        if (fileResults.isEmpty() && kbResults.isEmpty()) {
            log.info("[SimpleRetriever] 两路检索结果均为空");
            return Collections.emptyList();
        }
        if (fileResults.isEmpty()) {
            log.info("[SimpleRetriever] 文件检索结果为空，返回知识库检索结果");
            return kbResults.size() > topK ? kbResults.subList(0, topK) : kbResults;
        }
        if (kbResults.isEmpty()) {
            log.info("[SimpleRetriever] 知识库检索结果为空，返回文件检索结果");
            return fileResults.size() > topK ? fileResults.subList(0, topK) : fileResults;
        }

        // 使用 RRF 融合
        List<List<RagChunk>> rankedLists = new ArrayList<>();
        rankedLists.add(fileResults);
        rankedLists.add(kbResults);
        List<RagChunk> fused = ReciprocalRankFusion.fuse(rankedLists);

        // 截取 topK
        if (fused.size() > topK) {
            fused = fused.subList(0, topK);
        }

        log.info("[SimpleRetriever] 合并结果完成，fileResults={}, kbResults={}, merged={}",
                fileResults.size(), kbResults.size(), fused.size());
        return fused;
    }
}
