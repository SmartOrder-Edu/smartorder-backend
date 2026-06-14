package com.smartorder.edu.agent.rag.retriever;

import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RetrievalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中等检索器
 * 组合检索：向量检索 + 图检索，使用 Round-Robin 交替插入结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediumRetriever implements Retriever {

    private final SimpleRetriever simpleRetriever;
    private final GraphRagService graphRagService;

    @Override
    public RetrieverType type() {
        return RetrieverType.MEDIUM;
    }

    @Override
    public List<RagChunk> retrieve(RetrievalContext context) {
        log.info("[MediumRetriever] 开始中等检索，query={}", context.getQuery());

        try {
            // 1. 执行向量检索
            List<RagChunk> vectorChunks = simpleRetriever.retrieve(context);
            log.info("[MediumRetriever] 向量检索结果数量: {}", vectorChunks.size());

            // 2. 执行图检索
            List<RagChunk> graphChunks = graphRagService.retrieve(
                    context.getEntities(),
                    context.getKnowledgeBaseIds(),
                    context.getQuery(),
                    false
            );
            log.info("[MediumRetriever] 图检索结果数量: {}", graphChunks.size());

            // 3. Round-Robin 交替合并结果
            List<RagChunk> merged = roundRobinMerge(vectorChunks, graphChunks);
            log.info("[MediumRetriever] 中等检索完成，合并后结果数量: {}", merged.size());

            return merged;

        } catch (Exception e) {
            log.error("[MediumRetriever] 中等检索失败", e);
            // 降级到简单检索
            return simpleRetriever.retrieve(context);
        }
    }

    /**
     * Round-Robin 交替合并
     * 防止过度倾向单一策略
     */
    private List<RagChunk> roundRobinMerge(List<RagChunk> list1, List<RagChunk> list2) {
        List<RagChunk> merged = new ArrayList<>();
        int i = 0, j = 0;
        boolean turnList1 = true;

        while (i < list1.size() || j < list2.size()) {
            if (turnList1 && i < list1.size()) {
                merged.add(list1.get(i++));
            } else if (!turnList1 && j < list2.size()) {
                merged.add(list2.get(j++));
            }

            // 如果一方已用完，直接添加另一方
            if (i >= list1.size() && j < list2.size()) {
                merged.add(list2.get(j++));
                continue;
            }
            if (j >= list2.size() && i < list1.size()) {
                merged.add(list1.get(i++));
                continue;
            }

            turnList1 = !turnList1;
        }

        return merged;
    }
}
