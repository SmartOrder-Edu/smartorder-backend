package com.smartorder.edu.manage.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列与交换机声明配置类
 */
@Configuration
public class RabbitMqConfig {

    public static final String QUEUE_NAME = "feedback.prize.mail.queue";
    public static final String EXCHANGE_NAME = "feedback.prize.direct.exchange";
    public static final String ROUTING_KEY = "feedback.prize.mail.routing.key";

    /**
     * 声明卡密异步邮件投递队列
     */
    @Bean
    public Queue prizeMailQueue() {
        return new Queue(QUEUE_NAME, true); // 消息持久化
    }

    /**
     * 声明直连交换机 (Direct Exchange)
     */
    @Bean
    public DirectExchange prizeDirectExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * 绑定队列至交换机，通过专属 RoutingKey 路由
     */
    @Bean
    public Binding bindingPrizeMail() {
        return BindingBuilder.bind(prizeMailQueue())
                .to(prizeDirectExchange())
                .with(ROUTING_KEY);
    }

    /**
     * 注入 Jackson2 序列化转化器，保证消息以高可读的 JSON 格式在 AMQP 网路传输
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
