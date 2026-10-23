package com.smartorder.edu.student.service.impl;

import com.smartorder.edu.student.config.EvaluationRedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedRuntimeException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 测评模块 Redis 缓存读写封装
 *
 * <p>提供统一的 Cache-Aside 模式、缓存穿透防护（空值缓存）、
 * 缓存雪崩防护（TTL 随机 ±20% 抖动）等通用能力。
 * </p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // ==================== 通用缓存读写 ====================

    /**
     * 安全读取缓存值。
     *
     * <p>当 Redis 中存在与当前序列化器不兼容的旧数据（例如旧版本未启用
     * defaultTyping 写入的裸 JSON）时，反序列化会抛出
     * {@link org.springframework.data.redis.serializer.SerializationException}
     * 或被包装为 {@link org.springframework.data.redis.RedisSystemException}。
     * 此处统一捕获，视为缓存未命中：删除脏 key 并返回 null，由调用方回源 DB，
     * 避免单条坏数据导致整个业务请求失败。</p>
     */
    private Object safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (NestedRuntimeException e) {
            log.warn("缓存反序列化失败，删除脏数据并回源 DB: key={}, reason={}",
                    key, e.getMostSpecificCause().getMessage());
            try {
                redisTemplate.delete(key);
            } catch (Exception ignore) {
                // 删除失败不影响回源流程
            }
            return null;
        }
    }

    /**
     * Cache-Aside 读：先查缓存，miss 则查 DB 并回填
     *
     * @param key        Redis Key
     * @param ttl        缓存 TTL（自动加随机抖动）
     * @param type       反序列化目标类型
     * @param dbSupplier DB 查询回调
     * @param <T>        缓存值类型
     * @return 缓存值或 DB 查询结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Duration ttl, Class<T> type, Supplier<T> dbSupplier) {
        Object cached = safeGet(key);
        if (cached != null) {
            // 空值占位符 → 视为 miss
            if (cached instanceof Map<?, ?> m && m.containsKey("_empty_placeholder")) {
                log.debug("缓存命中空值占位符: key={}", key);
                return null;
            }
            log.debug("缓存命中: key={}", key);
            return (T) cached;
        }

        T result = dbSupplier.get();
        if (result != null) {
            redisTemplate.opsForValue().set(key, result, randomizeTtl(ttl));
        } else {
            // 缓存穿透防护：写入空值占位符
            setEmptyPlaceholder(key);
        }
        return result;
    }

    /**
     * Cache-Aside 读列表（含穿透防护）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getOrLoadList(String key, Duration ttl, Supplier<List<T>> dbSupplier) {
        Object cached = safeGet(key);
        if (cached != null) {
            if (cached instanceof Map<?, ?> m && m.containsKey("_empty_placeholder")) {
                log.debug("缓存命中空列表占位符: key={}", key);
                return List.of();
            }
            if (cached instanceof List<?>) {
                log.debug("列表缓存命中: key={}", key);
                return (List<T>) cached;
            }
        }

        List<T> result = dbSupplier.get();
        if (result != null && !result.isEmpty()) {
            redisTemplate.opsForValue().set(key, result, randomizeTtl(ttl));
        } else {
            // 空列表缓存 5 分钟防穿透
            redisTemplate.opsForValue().set(key, createEmptyPlaceholder(), EvaluationRedisConstants.TTL_EMPTY);
        }
        return result != null ? result : List.of();
    }

    // ==================== Hash 操作（草稿用） ====================

    /**
     * 读取 Hash 全部字段
     */
    public Map<Object, Object> hashGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * 写入 Hash 字段
     */
    public void hashPut(String key, Object field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 批量写入 Hash 字段并设置过期
     */
    public void hashPutAll(String key, Map<String, Object> map, Duration ttl) {
        redisTemplate.opsForHash().putAll(key, map);
        redisTemplate.expire(key, randomizeTtl(ttl));
    }

    /**
     * 删除 Hash 字段
     */
    public void hashDelete(String key, Object... fields) {
        redisTemplate.opsForHash().delete(key, fields);
    }

    // ==================== 缓存失效 ====================

    /**
     * 删除单个缓存 Key
     */
    public void delete(String key) {
        redisTemplate.delete(key);
        log.debug("缓存已删除: key={}", key);
    }

    /**
     * 按模式批量删除（如 {@code evaluation:questions:space:123:*} ）
     * <p>注意：生产环境慎用 keys 命令，此处用于低频的题目生成后清理。</p>
     */
    public void deleteByPattern(String pattern) {
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("批量删除缓存: pattern={}, count={}", pattern, keys.size());
        }
    }

    /**
     * 写入值并设置 TTL
     */
    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, randomizeTtl(ttl));
    }

    /**
     * 直接读取（不穿透 DB）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object cached = safeGet(key);
        if (cached instanceof Map<?, ?> m && m.containsKey("_empty_placeholder")) {
            return null;
        }
        return (T) cached;
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, Duration ttl) {
        redisTemplate.expire(key, randomizeTtl(ttl));
    }

    // ==================== 内部工具方法 ====================

    /**
     * TTL 随机 ±20% 抖动，防止缓存雪崩
     */
    static Duration randomizeTtl(Duration base) {
        long baseSeconds = base.toSeconds();
        if (baseSeconds <= 0) return base;
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // 0.8 ~ 1.2
        return Duration.ofSeconds(Math.max(1, (long) (baseSeconds * jitter)));
    }

    private void setEmptyPlaceholder(String key) {
        redisTemplate.opsForValue().set(key, createEmptyPlaceholder(), EvaluationRedisConstants.TTL_EMPTY);
    }

    private Map<String, Boolean> createEmptyPlaceholder() {
        return Map.of("_empty_placeholder", true);
    }
}
