package com.smartorder.edu.agent.rag.rerank;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) 算法实现
 * 用于融合多路检索结果
 */
@Slf4j
public class ReciprocalRankFusion {

    /**
     * RRF 默认参数 k
     */
    private static final int DEFAULT_K = 60;

    /**
     * 使用默认参数执行 RRF 融合
     *
     * @param rankedLists 多个排序列表
     * @return 融合后的结果
     */
    public static <T> List<T> fuse(List<List<T>> rankedLists) {
        return fuse(rankedLists, DEFAULT_K);
    }

    /**
     * 执行 RRF 融合
     *
     * @param rankedLists 多个排序列表
     * @param k           RRF 参数 k
     * @return 融合后的结果
     */
    public static <T> List<T> fuse(List<List<T>> rankedLists, int k) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return new ArrayList<>();
        }

        // 计算每个元素的 RRF 分数
        Map<T, Double> scoreMap = new LinkedHashMap<>();

        for (List<T> rankedList : rankedLists) {
            for (int rank = 0; rank < rankedList.size(); rank++) {
                T item = rankedList.get(rank);
                double rrfScore = 1.0 / (k + rank + 1);
                scoreMap.merge(item, rrfScore, Double::sum);
            }
        }

        // 按分数降序排序
        List<T> result = new ArrayList<>(scoreMap.keySet());
        result.sort((a, b) -> Double.compare(scoreMap.get(b), scoreMap.get(a)));

        log.debug("[ReciprocalRankFusion] 融合完成，输入列表数: {}, 结果数: {}",
                rankedLists.size(), result.size());

        return result;
    }

    /**
     * 带权重的 RRF 融合
     *
     * @param rankedLists 多个排序列表
     * @param weights     每个列表的权重
     * @param k           RRF 参数 k
     * @return 融合后的结果
     */
    public static <T> List<T> fuseWithWeights(List<List<T>> rankedLists, List<Double> weights, int k) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return new ArrayList<>();
        }

        if (weights == null || weights.size() != rankedLists.size()) {
            log.warn("[ReciprocalRankFusion] 权重列表大小不匹配，使用默认权重");
            return fuse(rankedLists, k);
        }

        // 计算每个元素的加权 RRF 分数
        Map<T, Double> scoreMap = new LinkedHashMap<>();

        for (int listIdx = 0; listIdx < rankedLists.size(); listIdx++) {
            List<T> rankedList = rankedLists.get(listIdx);
            double weight = weights.get(listIdx);

            for (int rank = 0; rank < rankedList.size(); rank++) {
                T item = rankedList.get(rank);
                double rrfScore = weight / (k + rank + 1);
                scoreMap.merge(item, rrfScore, Double::sum);
            }
        }

        // 按分数降序排序
        List<T> result = new ArrayList<>(scoreMap.keySet());
        result.sort((a, b) -> Double.compare(scoreMap.get(b), scoreMap.get(a)));

        log.debug("[ReciprocalRankFusion] 带权重融合完成，输入列表数: {}, 结果数: {}",
                rankedLists.size(), result.size());

        return result;
    }
}
