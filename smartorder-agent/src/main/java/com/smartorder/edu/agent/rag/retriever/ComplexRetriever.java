package com.smartorder.edu.agent.rag.retriever;

import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RetrievalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 复杂检索器
 * 图 RAG 检索（多跳推理 + 子图提取）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplexRetriever implements Retriever {

    private final GraphRagService graphRagService;

    @Override
    public RetrieverType type() {
        return RetrieverType.COMPLEX;
    }

    @Override
    public List<RagChunk> retrieve(RetrievalContext context) {
        log.info("[ComplexRetriever] 开始复杂检索，query={}, entities={}",
                context.getQuery(), context.getEntities());

        List<String> entities = context.getEntities();
        if (entities == null || entities.isEmpty()) {
            log.warn("[ComplexRetriever] 无实体信息，无法执行图检索");
            return Collections.emptyList();
        }

        List<Long> knowledgeBaseIds = context.getKnowledgeBaseIds();
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            log.warn("[ComplexRetriever] 未指定知识库ID");
            return Collections.emptyList();
        }

        return graphRagService.retrieve(entities, knowledgeBaseIds, context.getQuery(), true);
    }
}
