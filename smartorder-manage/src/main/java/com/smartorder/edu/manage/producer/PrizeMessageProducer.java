package com.smartorder.edu.manage.producer;

import com.smartorder.edu.manage.consumer.PrizeMailConsumer;
import com.smartorder.edu.manage.domain.dto.PrizeMailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 奖品卡密异步邮件消息生产者 (Producer Facade)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrizeMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final PrizeMailConsumer mailConsumer;

    /**
     * 送出异步卡密信箱消息，若本地 MQ 未启动则自动平滑降级，确保业务 100% 零阻碍
     */
    public void sendPrizeMailMessage(PrizeMailMessage message) {
        try {
            // 通过 Spring AMQP 投递进入 RabbitMQ 异步队列
            rabbitTemplate.convertAndSend(
                    "feedback.prize.direct.exchange",
                    "feedback.prize.mail.routing.key",
                    message
            );
            log.info("[RabbitMQ 生产者投递成功] 奖品卡密异步邮件消息已成功送入 AMQP 队列: {}", message.getPrizeCode());
        } catch (Exception e) {
            log.warn("[RabbitMQ 生产者连接警告] 本地 RabbitMQ 消息代理物理服务未启动，系统触发“高可用平滑安全降级机制”：秒级切流至本地多线程池异步发送邮件！");
            
            // 本地线程池异步模拟派发：切断对物理 MQ-Server 强依赖，保护宿主环境零阻碍运行
            new Thread(() -> {
                try {
                    Thread.sleep(150); // 模拟网络延迟
                    mailConsumer.receivePrizeMail(message);
                } catch (Exception ex) {
                    log.error("本地降级异步发送兑换码邮件异常", ex);
                }
            }).start();
        }
    }
}
