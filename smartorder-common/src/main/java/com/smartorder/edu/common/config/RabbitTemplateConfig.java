package com.smartorder.edu.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitTemplate 全局配置。
 * <p>
 * 发布确认（publisher confirm）回调是 {@link RabbitTemplate} 这个<b>单例 Bean 的全局状态</b>，
 * 必须在容器启动阶段设置一次。之前写在 {@code UserServiceImpl.insertUser} 里，会带来两个问题：
 * <ul>
 *   <li>每注册一个用户就覆盖一次回调，前面的设置全部作废；</li>
 *   <li>{@code setConfirmCallback} 是非线程安全的字段写入，并发注册时多线程同时改同一个字段。</li>
 * </ul>
 * 单条消息的投递结果请用 {@code CorrelationData#getFuture()}，那是 per-message 的，与此处互不影响。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitTemplateConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void initPublisherConfirmCallback() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            // CorrelationData.id 由发送方指定（业务侧通常传业务主键），用于把回调关联回具体消息
            String id = (correlationData != null && correlationData.getId() != null)
                    ? correlationData.getId()
                    : "unknown";
            if (ack) {
                log.debug("[MQ] 消息已抵达 Broker, id: {}", id);
            } else {
                log.error("[MQ] 消息投递失败（Broker 未确认）, id: {}, reason: {}", id, cause);
            }
        });
        log.info("[MQ] RabbitTemplate 发布确认回调已初始化");
    }
}
