package com.smartorder.edu.common.config;


import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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

    public static final String QUEUE_FILE_PROCESS = "knowledge.file.process.queue";
    public static final String ROUTING_KEY_FILE_PROCESS = "knowledge.file.process.key";

    public static final String QUEUE_FILE_PROCESS_DLQ = "knowledge.file.process.dlq";
    public static final String ROUTING_KEY_FILE_PROCESS_DLQ = "knowledge.file.process.dlq.key";

    /**
     * 配置 JSON 转换器，确保消息以 JSON 格式序列化/反序列化
     * 使用 INFERRED 类型优先级，让 @RabbitListener 方法参数类型决定反序列化目标，
     * 而非依赖 __TypeId__ header，避免发送端与接收端类型不匹配的问题。
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
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

    @Bean
    public Queue fileProcessQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", KNOWLEDGE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ROUTING_KEY_FILE_PROCESS_DLQ);
        return new Queue(QUEUE_FILE_PROCESS, true, false, false, args);
    }


    @Bean
    public Binding bindingFileProcess(
            @Qualifier("fileProcessQueue") Queue fileProcessQueue,
            @Qualifier("knowledgeExchange") DirectExchange knowledgeExchange
    ) {
        return BindingBuilder.bind(fileProcessQueue).to(knowledgeExchange).with(ROUTING_KEY_FILE_PROCESS);
    }


    @Bean
    public Queue fileProcessDlq() {
        return new Queue(QUEUE_FILE_PROCESS_DLQ, true);
    }


    @Bean
    public Binding bindingFileProcessDlq(
            @Qualifier("fileProcessDlq") Queue fileProcessDlq,
            @Qualifier("knowledgeExchange") DirectExchange knowledgeExchange
    ) {
        return BindingBuilder.bind(fileProcessDlq).to(knowledgeExchange).with(ROUTING_KEY_FILE_PROCESS_DLQ);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        return factory;
    }

}