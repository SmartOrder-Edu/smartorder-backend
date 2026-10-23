package com.smartorder.edu.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建支持 Java 8 日期类型的 JSON 序列化器
     * <p>GenericJackson2JsonRedisSerializer 的无参构造器使用裸 ObjectMapper，
     * 无法处理 LocalDateTime 等类型。这里手动注册 JavaTimeModule 并禁
     * 用将日期写为时间戳，使得序列化为可读的 ISO-8601 字符串。</p>
     */
    private static GenericJackson2JsonRedisSerializer createJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 启用默认类型记录（EVERYTHING，与 GenericJackson2JsonRedisSerializer 无参构造器行为一致）
        // 序列化为 ["类型全限定名", 值] 结构，反序列化时才能恢复 List/Map/DTO 等具体类型。
        // 注意：此格式与「未启用 defaultTyping 时写入的裸 JSON」不兼容，
        // 历史脏数据需在读取层容错（见 EvaluationCacheService#safeGet）或清理后重建。
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING
        );
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        var serializer = createJsonSerializer();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}