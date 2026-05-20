package com.smartorder.edu.common.rag.vector;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static final String REDIS_KEY_PREFIX = "rag:tfidf:";
    private static final String VOCABULARY_KEY = REDIS_KEY_PREFIX + "vocabulary";
    private static final String DOC_FREQ_KEY = REDIS_KEY_PREFIX + "doc_freq";
    private static final String META_KEY = REDIS_KEY_PREFIX + "meta";

    @Value("${tfidf.max-vocabulary-size:100000}")
    private int maxVocabularySize;

    @Value("${tfidf.min-term-frequency:2}")
    private int minTermFrequency;

    private final StringRedisTemplate redisTemplate;
    private SmartChineseAnalyzer analyzer;

    public TfidfSparseVectorService(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

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
        loadFromRedis();
        log.info("TF-IDF 服务初始化完成，最大词汇表大小: {}，当前词汇量: {}", maxVocabularySize, vocabulary.size());
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

        // 持久化到 Redis
        saveToRedis();

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

    /**
     * 保存词汇表到 Redis
     */
    private void saveToRedis() {
        try {
            // 1. 保存词汇表 vocabulary
            if (!vocabulary.isEmpty()) {
                Map<String, String> vocabMap = new HashMap<>();
                vocabulary.forEach((k, v) -> vocabMap.put(k, String.valueOf(v)));
                redisTemplate.opsForHash().putAll(VOCABULARY_KEY, vocabMap);
            }

            // 2. 保存文档频率 documentFrequency
            if (!documentFrequency.isEmpty()) {
                Map<String, String> dfMap = new HashMap<>();
                documentFrequency.forEach((k, v) -> dfMap.put(k, String.valueOf(v.get())));
                redisTemplate.opsForHash().putAll(DOC_FREQ_KEY, dfMap);
            }

            // 3. 保存元数据
            Map<String, String> metaMap = new HashMap<>();
            metaMap.put("totalDocuments", String.valueOf(totalDocuments.get()));
            metaMap.put("currentIndex", String.valueOf(currentIndex.get()));
            redisTemplate.opsForHash().putAll(META_KEY, metaMap);

            log.info("词汇表已保存到 Redis，词汇量: {}", vocabulary.size());
        } catch (Exception e) {
            log.error("保存词汇表到 Redis 失败", e);
        }
    }

    /**
     * 从 Redis 加载词汇表
     */
    private void loadFromRedis() {
        try {
            // 1. 加载元数据
            Map<Object, Object> metaMap = redisTemplate.opsForHash().entries(META_KEY);
            if (metaMap.isEmpty()) {
                log.info("Redis 中无词汇表数据，使用空词汇表");
                return;
            }

            if (metaMap.containsKey("totalDocuments")) {
                totalDocuments.set(Long.parseLong((String) metaMap.get("totalDocuments")));
            }
            if (metaMap.containsKey("currentIndex")) {
                currentIndex.set(Long.parseLong((String) metaMap.get("currentIndex")));
            }

            // 2. 加载词汇表
            Map<Object, Object> vocabMap = redisTemplate.opsForHash().entries(VOCABULARY_KEY);
            vocabulary.clear();
            vocabMap.forEach((k, v) -> vocabulary.put((String) k, Long.parseLong((String) v)));

            // 3. 加载文档频率
            Map<Object, Object> dfMap = redisTemplate.opsForHash().entries(DOC_FREQ_KEY);
            documentFrequency.clear();
            dfMap.forEach((k, v) -> documentFrequency.put((String) k, new AtomicLong(Long.parseLong((String) v))));

            log.info("从 Redis 加载词汇表完成，词汇量: {}，总文档数: {}", vocabulary.size(), totalDocuments.get());
        } catch (Exception e) {
            log.error("从 Redis 加载词汇表失败，使用空词汇表", e);
            vocabulary.clear();
            documentFrequency.clear();
            totalDocuments.set(0);
            currentIndex.set(0);
        }
    }
}
