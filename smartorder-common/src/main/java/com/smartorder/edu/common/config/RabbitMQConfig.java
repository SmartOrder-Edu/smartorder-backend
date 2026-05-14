package com.smartorder.edu.common.config;


import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *  RabbitMQ 配置类
 */
@Configuration
public class RabbitMQConfig {

    // 交换机
    public static final String KNOWLEDGE_EXCHANGE = "knowledge.base.exchange";

    // 队列
    public static final String QUEUE_FILE_DB_SAVE = "knowledge.file.db.save.queue";

    // 路由键
    public static final String ROUTING_KEY_FILE_DB_SAVE = "knowledge.file.db.save.key";

    /**
     * 配置 JSON 转换器，确保消息以 JSON 格式序列化/反序列化
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange knowledgeExchange() {
        return new DirectExchange(KNOWLEDGE_EXCHANGE);
    }

    @Bean
    public Queue fileDbSaveQueue() {
        // durable = true 持久化队列
        return new Queue(QUEUE_FILE_DB_SAVE, true);
    }

    @Bean
    public Binding bindingFileDbSave(@Qualifier("fileDbSaveQueue") Queue fileDbSaveQueue,
                                     @Qualifier("knowledgeExchange") DirectExchange knowledgeExchange) {
        return BindingBuilder.bind(fileDbSaveQueue).to(knowledgeExchange).with(ROUTING_KEY_FILE_DB_SAVE);
    }
}