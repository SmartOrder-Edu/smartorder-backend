package com.smartorder.edu.student.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 刷题提醒 RabbitMQ 配置
 * <p>
 * 使用 TTL + Dead Letter Exchange 实现延迟消息，无需额外插件。
 * 每条消息发布到对应阶段的 TTL 队列，TTL 到期后自动转入处理队列由消费者消费。
 * </p>
 *
 * <pre>
 * Publisher ──→ stage1.queue (TTL=1min) ──→ DLX ──→ process.queue ──→ Listener
 *            ──→ stage2.queue (TTL=3min) ──→ DLX ──→ process.queue ──→ Listener
 * </pre>
 *
 * @author SmartOrder
 * @since 2026-07-10
 */
@Configuration
public class PracticeReminderRabbitConfig {

    // ==================== 路由常量 ====================

    public static final String DLX_EXCHANGE = "practice.reminder.dlx";
    public static final String PROCESS_QUEUE = "practice.reminder.process.queue";
    public static final String PROCESS_ROUTING_KEY = "practice.reminder.process";

    public static final String STAGE1_QUEUE = "practice.reminder.stage1.queue";
    public static final String STAGE2_QUEUE = "practice.reminder.stage2.queue";

    // ==================== 可配置延迟 ====================

    @Value("${practice.reminder.stage1-delay:1}")
    private long stage1DelayMinutes;

    @Value("${practice.reminder.stage2-delay:2}")
    private long stage2DelayMinutes;

    // ==================== DLX 交换机 ====================

    @Bean
    public DirectExchange reminderDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ==================== 处理队列（消费者监听） ====================

    @Bean
    public Queue reminderProcessQueue() {
        return QueueBuilder.durable(PROCESS_QUEUE)
                .build();
    }

    @Bean
    public Binding reminderProcessBinding() {
        return BindingBuilder.bind(reminderProcessQueue())
                .to(reminderDlxExchange())
                .with(PROCESS_ROUTING_KEY);
    }

    // ==================== Stage1 TTL 队列（无消费者，到期转入 DLX） ====================

    @Bean
    public Queue reminderStage1TtlQueue() {
        return QueueBuilder.durable(STAGE1_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PROCESS_ROUTING_KEY)
                .withArgument("x-message-ttl", stage1DelayMinutes * 60 * 1000)
                .build();
    }

    // ==================== Stage2 TTL 队列（无消费者，到期转入 DLX） ====================

    @Bean
    public Queue reminderStage2TtlQueue() {
        return QueueBuilder.durable(STAGE2_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PROCESS_ROUTING_KEY)
                .withArgument("x-message-ttl", stage2DelayMinutes * 60 * 1000)
                .build();
    }

}
