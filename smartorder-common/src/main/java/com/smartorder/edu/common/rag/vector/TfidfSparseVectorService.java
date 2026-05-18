package com.smartorder.edu.common.rag.vector;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TF-IDF 稀疏向量服务
 * 使用 Lucene SmartChineseAnalyzer 进行中文分词，计算 TF-IDF 权重
 */
@Slf4j
@Service
public class TfidfSparseVectorService {

    @Value("${tfidf.max-vocabulary-size:100000}")
    private int maxVocabularySize;

    @Value("${tfidf.min-term-frequency:2}")
    private int minTermFrequency;

    private SmartChineseAnalyzer analyzer;

    // 词汇表：term -> index
    private final Map<String, Long> vocabulary = new ConcurrentHashMap<>();

    // 文档频率：term -> 出现该词的文档数
    private final Map<String, AtomicLong> documentFrequency = new ConcurrentHashMap<>();

    // 总文档数
    private final AtomicLong totalDocuments = new AtomicLong(0);

    // 当前词汇表索引
    private final AtomicLong currentIndex = new AtomicLong(0);

    @PostConstruct
    public void init() {
        analyzer = new SmartChineseAnalyzer();
        log.info("TF-IDF 服务初始化完成，最大词汇表大小: {}", maxVocabularySize);
    }

    @PreDestroy
    public void destroy() {
        if (analyzer != null) {
            analyzer.close();
        }
    }

    /**
     * 将文本转换为 TF-IDF 稀疏向量
     *
     * @param text 输入文本
     * @return 稀疏向量 Map<index, value>
     */
    public Map<Long, Float> computeTfidfVector(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }

        // 1. 分词
        List<String> terms = tokenize(text);
        if (terms.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 计算词频 (TF)
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : terms) {
            termFrequency.merge(term, 1, Integer::sum);
        }

        // 3. 计算 TF-IDF
        Map<Long, Float> sparseVector = new HashMap<>();
        int totalTerms = terms.size();

        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            String term = entry.getKey();
            int tf = entry.getValue();

            // 获取或创建词汇表索引
            Long index = vocabulary.get(term);
            if (index == null) {
                // 对于新词，临时分配索引（不更新全局词汇表）
                continue;
            }

            // 获取文档频率
            long df = documentFrequency.containsKey(term) ? documentFrequency.get(term).get() : 1;

            // 计算 TF-IDF
            // TF = 词频 / 总词数
            // IDF = log(总文档数 / (文档频率 + 1)) + 1
            float tfScore = (float) tf / totalTerms;
            float idfScore = (float) (Math.log((double) totalDocuments.get() / (df + 1)) + 1);
            float tfidf = tfScore * idfScore;

            if (tfidf > 0) {
                sparseVector.put(index, tfidf);
            }
        }

        return sparseVector;
    }

    /**
     * 批量计算 TF-IDF 稀疏向量
     *
     * @param texts 文本列表
     * @return 稀疏向量列表
     */
    public List<Map<Long, Float>> computeTfidfVectors(List<String> texts) {
        List<Map<Long, Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(computeTfidfVector(text));
        }
        return results;
    }

    /**
     * 更新词汇表和文档频率
     * 应在索引新文档时调用
     *
     * @param documents 文档列表
     */
    public void updateVocabulary(List<String> documents) {
        log.info("开始更新词汇表，文档数量: {}", documents.size());

        for (String doc : documents) {
            List<String> terms = tokenize(doc);
            Set<String> uniqueTerms = new HashSet<>(terms);

            // 更新文档频率
            for (String term : uniqueTerms) {
                documentFrequency.computeIfAbsent(term, k -> new AtomicLong(0)).incrementAndGet();

                // 更新词汇表
                if (!vocabulary.containsKey(term) && vocabulary.size() < maxVocabularySize) {
                    vocabulary.computeIfAbsent(term, k -> currentIndex.getAndIncrement());
                }
            }

            totalDocuments.incrementAndGet();
        }

        log.info("词汇表更新完成，当前词汇量: {}，总文档数: {}", vocabulary.size(), totalDocuments.get());
    }

    /**
     * 文本分词
     *
     * @param text 输入文本
     * @return 分词结果列表
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();

        try (TokenStream tokenStream = analyzer.tokenStream("content", text)) {
            CharTermAttribute charTermAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            while (tokenStream.incrementToken()) {
                String term = charTermAttr.toString();
                if (term != null && !term.isBlank() && term.length() > 1) {
                    tokens.add(term);
                }
            }

            tokenStream.end();
        } catch (IOException e) {
            log.error("分词失败: {}", text, e);
        }

        return tokens;
    }

    /**
     * 获取词汇表
     *
     * @return 词汇表 Map<term, index>
     */
    public Map<String, Long> getVocabulary() {
        return Collections.unmodifiableMap(vocabulary);
    }

    /**
     * 获取词汇表大小
     *
     * @return 词汇表大小
     */
    public int getVocabularySize() {
        return vocabulary.size();
    }

    /**
     * 获取总文档数
     *
     * @return 总文档数
     */
    public long getTotalDocuments() {
        return totalDocuments.get();
    }

    /**
     * 保存词汇表到文件（用于持久化）
     *
     * @return 词汇表数据
     */
    public Map<String, Object> exportVocabulary() {
        Map<String, Object> data = new HashMap<>();
        data.put("vocabulary", new HashMap<>(vocabulary));
        data.put("documentFrequency", new HashMap<>());
        documentFrequency.forEach((k, v) -> data.put("df_" + k, v.get()));
        data.put("totalDocuments", totalDocuments.get());
        data.put("currentIndex", currentIndex.get());
        return data;
    }

    /**
     * 从文件加载词汇表（用于恢复）
     *
     * @param data 词汇表数据
     */
    @SuppressWarnings("unchecked")
    public void importVocabulary(Map<String, Object> data) {
        if (data.containsKey("vocabulary")) {
            Map<String, Long> vocab = (Map<String, Long>) data.get("vocabulary");
            vocabulary.clear();
            vocabulary.putAll(vocab);
        }

        if (data.containsKey("totalDocuments")) {
            totalDocuments.set((Long) data.get("totalDocuments"));
        }

        if (data.containsKey("currentIndex")) {
            currentIndex.set((Long) data.get("currentIndex"));
        }

        // 恢复文档频率
        documentFrequency.clear();
        data.forEach((k, v) -> {
            if (k.startsWith("df_")) {
                String term = k.substring(3);
                documentFrequency.put(term, new AtomicLong((Long) v));
            }
        });

        log.info("词汇表加载完成，词汇量: {}，总文档数: {}", vocabulary.size(), totalDocuments.get());
    }
}
