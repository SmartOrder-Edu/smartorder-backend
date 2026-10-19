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
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
     * <p>
     * 标 {@code @Primary}：manage 模块的 RabbitMqConfig 也声明了一个 MessageConverter，
     * 聚合启动（smartorder-web）时容器里会有两个 MessageConverter 候选，
     * Spring Boot 的 RabbitTemplateConfigurer / SimpleRabbitListenerContainerFactoryConfigurer
     * 按类型取 bean 时会因非唯一而拿不到（或直接抛异常）。此处显式指定首选，行为确定。
     */
    @Bean
    @Primary
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

    /**
     * 自定义容器工厂。
     * <p>
     * 注意：一旦手动声明了这个 Bean，Spring Boot 的自动配置就不再接管，
     * 因此必须注入 {@link SimpleRabbitListenerContainerFactoryConfigurer} 并调用 {@code configure()}，
     * 否则 application-common.yml 里 {@code spring.rabbitmq.listener.simple.*} 下配置的
     * retry（max-attempts 重试次数、退避间隔）、prefetch、concurrency 全部**静默失效**。
     * <p>
     * 配合各业务队列的 {@code x-dead-letter-exchange}，重试耗尽后的消息会进死信队列，
     * 而不是被 basicReject 后直接丢弃。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // 自定义 JSON 转换器需显式挂载（INFERRED 类型推断，参数类型决定反序列化目标）
        factory.setMessageConverter(jsonMessageConverter());
        // 重试耗尽后拒绝且不重回队列（false），交给队列的死信交换机兜底
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

}