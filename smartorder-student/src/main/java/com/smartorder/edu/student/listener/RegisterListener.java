package com.smartorder.edu.student.listener;

import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 用户注册监听器 - 可以赠送一些优惠等等
 * 学生模块：为新用户初始化云盘配额
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegisterListener {
    private final IUserFileQuotaService userFileQuotaService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "users.student.register.queue", durable = "true"),
            exchange = @Exchange(name = "smartOrder.user.file.quota.direct", type = ExchangeTypes.DIRECT, durable = "true"),
            key = {"user.file.quota"}
    ))
    public void handleUserRegistered(Object message) {
        Long userId = (Long) message;
        log.info("为新用户 {} 扩展一下 用户云盘", userId);
        if (userId != null) {
            userFileQuotaService.initQuota(userId);
        } else {
            log.warn("消息格式不正确，无法处理: {}", message);
        }
    }

}
