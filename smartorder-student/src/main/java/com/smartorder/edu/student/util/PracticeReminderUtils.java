package com.smartorder.edu.student.util;

import com.smartorder.edu.student.config.PracticeReminderRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 刷题提醒工具类
 * <p>
 * 基于 RabbitMQ TTL + Dead Letter Exchange 实现延迟提醒。
 * 消息发布到各阶段的 TTL 队列，TTL 到期后自动转入处理队列供消费者消费。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 开始刷题时，注册提醒
 * practiceReminderUtils.schedule(sessionId, userId);
 *
 * // 完成刷题时，取消提醒（消息到达时消费者会校验 status，此处无需操作）
 * practiceReminderUtils.cancel(sessionId, userId);
 * }</pre>
 *
 * <h3>消息流转</h3>
 * <pre>
 * publish → stage{N}.queue (TTL) → DLX → process.queue → Listener
 * </pre>
 *
 * @author SmartOrder
 * @since 2026-07-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PracticeReminderUtils {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 为指定的测评任务注册延迟提醒
     * <p>向 stage1 和 stage2 的 TTL 队列各发送一条消息，消息在 TTL 到期后自动转入处理队列</p>
     *
     * @param sessionId 测评会话 ID
     * @param userId    用户 ID
     */
    public void schedule(Long sessionId, Long userId) {
        // stage1: 第一次提醒
        Map<String, Object> msg1 = Map.of(
                "sessionId", sessionId,
                "userId", userId,
                "stage", 1
        );
        rabbitTemplate.convertAndSend(PracticeReminderRabbitConfig.STAGE1_QUEUE, msg1);
        log.info("已注册刷题提醒 stage1: sessionId={}, userId={}", sessionId, userId);

        // stage2: 第二次提醒
        Map<String, Object> msg2 = Map.of(
                "sessionId", sessionId,
                "userId", userId,
                "stage", 2
        );
        rabbitTemplate.convertAndSend(PracticeReminderRabbitConfig.STAGE2_QUEUE, msg2);
        log.info("已注册刷题提醒 stage2: sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * 取消指定测评任务的所有提醒
     * <p>消息到达处理队列时消费者会校验 session 状态，已完成的任务会自动跳过，
     * 因此这里不需要主动删除消息。</p>
     *
     * @param sessionId 测评会话 ID
     * @param userId    用户 ID
     */
    public void cancel(Long sessionId, Long userId) {
        log.info("刷题提醒已标记取消（消息到达时将校验状态自动跳过）: sessionId={}, userId={}",
                sessionId, userId);
    }
}
