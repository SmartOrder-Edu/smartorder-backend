package com.smartorder.edu.agent.rag.rerank;

import com.smartorder.edu.agent.rag.model.RagChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RRF 重排器
 * 使用 Reciprocal Rank Fusion 算法对多路召回结果进行重排
 */
@Slf4j
@Component
public class RrfReranker {

    /**
     * 对单路检索结果进行重排（按分数排序）
     */
    public List<RagChunk> rerank(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        // 按分数降序排序
        List<RagChunk> sorted = new ArrayList<>(chunks);
        sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        // 去重（基于 vectorId）
        return deduplicate(sorted);
    }

    /**
     * 对多路召回结果进行 RRF 融合重排
     *
     * @param multiResults 多路检索结果，每路是一个 List<RagChunk>
     * @return 融合重排后的结果
     */
    public List<RagChunk> rerankMultiRoute(List<List<RagChunk>> multiResults) {
        if (multiResults == null || multiResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 如果只有一路结果，直接重排
        if (multiResults.size() == 1) {
            return rerank(multiResults.get(0));
        }

        log.info("[RrfReranker] 开始多路 RRF 融合重排，路数: {}", multiResults.size());

        // 为每路结果按分数排序，生成排名列表
        List<List<String>> rankedIds = new ArrayList<>();
        for (List<RagChunk> result : multiResults) {
            List<String> ids = result.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .map(RagChunk::getVectorId)
                    .collect(Collectors.toList());
            rankedIds.add(ids);
        }

        // 执行 RRF 融合
        List<String> fusedIds = ReciprocalRankFusion.fuse(rankedIds);

        // 构建 vectorId -> RagChunk 的映射
        Map<String, RagChunk> chunkMap = new HashMap<>();
        for (List<RagChunk> result : multiResults) {
            for (RagChunk chunk : result) {
                chunkMap.putIfAbsent(chunk.getVectorId(), chunk);
            }
        }

        // 按 RRF 融合顺序组装结果
        List<RagChunk> fusedResult = new ArrayList<>();
        for (String id : fusedIds) {
            RagChunk chunk = chunkMap.get(id);
            if (chunk != null) {
                fusedResult.add(chunk);
            }
        }

        // 去重
        List<RagChunk> deduplicated = deduplicate(fusedResult);

        log.info("[RrfReranker] RRF 融合重排完成，结果数量: {}", deduplicated.size());
        return deduplicated;
    }

    /**
     * 去重
     */
    private List<RagChunk> deduplicate(List<RagChunk> chunks) {
        Map<String, RagChunk> seen = new HashMap<>();
        List<RagChunk> result = new ArrayList<>();

        for (RagChunk chunk : chunks) {
            String key = chunk.getVectorId();
            if (key == null) {
                key = chunk.getContent() != null ? chunk.getContent().hashCode() + "" : null;
            }
            if (key != null && !seen.containsKey(key)) {
                seen.put(key, chunk);
                result.add(chunk);
            }
        }

        return result;
    }
}
