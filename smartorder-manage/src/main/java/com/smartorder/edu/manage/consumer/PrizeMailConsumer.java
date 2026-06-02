package com.smartorder.edu.manage.consumer;

import com.smartorder.edu.manage.domain.dto.PrizeMailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 异步信件接收消费者监听器
 */
@Slf4j
@Component
@RabbitListener(queues = "feedback.prize.mail.queue")
public class PrizeMailConsumer {

    /**
     * 异步接收分配成功的卡密信息，模拟调用 SMTP 信箱服务发送邮件
     */
    @RabbitHandler
    public void receivePrizeMail(PrizeMailMessage message) {
        log.info("[RabbitMQ 消费者监听到消息] 开始执行异步邮件发放任务...");
        log.info("--------------------------- 发送系统奖励信件 ---------------------------");
        log.info("收件人 (中奖用户 ID): {}", message.getUserId());
        log.info("收件人联系邮箱: {}", message.getEmail() != null && !message.getEmail().trim().isEmpty() ? message.getEmail() : "user_" + message.getUserId() + "@知序智学.edu");
        log.info("获得奖励品类: {}", message.getPrizeName());
        log.info("系统派发唯一卡密兑换码: 【 {} 】", message.getPrizeCode());
        log.info("关联反馈记录ID: {}", message.getFeedbackId());
        log.info("[SMTP 邮件代理] 信件数据包已被 SMTP 网关成功投递并签收！");
        log.info("----------------------------------------------------------------------");
    }
}
