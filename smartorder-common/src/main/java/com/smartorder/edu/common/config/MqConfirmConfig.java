package com.smartorder.edu.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;

/**
 * 生产者回调机制-主要解决以下问题-保留日志等等
 * 1. routingKey 写错了 — 交换机没有与之绑定的路由键
 * 2. 目标队列不存在 — 队列还没创建就被投递
 * 3. 绑定关系缺失 — 交换机存在但没绑定任何队列
 * RabbitMQ 消息发送失败回调配置类
 * 通过实现 ApplicationContextAware 接口，在 Spring 容器初始化完成后获取 RabbitTemplate，
 * 并设置 ReturnCallback（消息返回回调）来处理消息发送失败的情况。
 * 当生产者发送消息到 RabbitMQ 时，如果消息到达交换机（Exchange）但无法路由到任何队列
 * （例如：路由键 routingKey 错误、目标队列不存在），RabbitMQ 会触发 ReturnCallback，
 * 该类会捕获并记录失败信息（包括交换机、路由键、消息内容、错误码、错误文本等），帮助开发者排查消息投递问题。
 */
@Slf4j
@Configuration
public class MqConfirmConfig implements ApplicationContextAware {
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        // 获取 RabbitTemplate
        RabbitTemplate rabbitTemplate = applicationContext.getBean(RabbitTemplate.class);

        // 设置 mandatory=true，确保消息无法路由时触发 ReturnCallback 而非静默丢弃
        rabbitTemplate.setMandatory(true);

        // 设置 ReturnCallback
        rabbitTemplate.setReturnsCallback(new RabbitTemplate.ReturnsCallback() {
            @Override
            public void returnedMessage(ReturnedMessage returnedMessage) {
                log.debug("消息发送失败: " + returnedMessage.getMessage());
                log.info("收到消息的return callback: ,exchange: {}, routingKey: {}, " +
                                "message: {}, replyCode: {}, replyText: {}",
                        returnedMessage.getExchange(), returnedMessage.getRoutingKey(),
                        returnedMessage.getMessage(), returnedMessage.getReplyCode(),
                        returnedMessage.getReplyText()
                );
            }
        });
    }
}
