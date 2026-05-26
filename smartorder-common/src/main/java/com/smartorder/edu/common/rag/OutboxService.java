package com.smartorder.edu.common.rag;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.common.mapper.MessageOutboxMapper;
import com.smartorder.edu.common.model.entity.MessageOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.smartorder.edu.common.common.Constants.FAILED;
import static com.smartorder.edu.common.common.Constants.PENDING;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final MessageOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public void saveOutbox(String topic, String exchange, String routingKey, Object payload) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setTopic(topic);
        outbox.setExchangeName(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setPayload(JSON.toJSONString(payload));
        outbox.setStatus(PENDING);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(LocalDateTime.now());
        outbox.setUpdatedAt(LocalDateTime.now());
        outboxMapper.insert(outbox);
    }


    @Scheduled(fixedDelay = 3000)
    public void flushPendingMessages() {
        log.info("rag定时任务开始执行");
        LambdaQueryWrapper<MessageOutbox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageOutbox::getStatus,PENDING)
                .lt(MessageOutbox::getRetryCount, 5)
                .orderByAsc(MessageOutbox::getId)
                .last("LIMIT 50");

        List<MessageOutbox> list = outboxMapper.selectList(wrapper);
        for (MessageOutbox outbox : list) {
            try {
                rabbitTemplate.convertAndSend(outbox.getExchangeName(), outbox.getRoutingKey(), outbox.getPayload());
                log.info("rag定时任务执行成功");
                outbox.setStatus("sent");
                outbox.setUpdatedAt(LocalDateTime.now());
                outboxMapper.updateById(outbox);
            } catch (Exception e) {
                outbox.setRetryCount(outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1);
                outbox.setErrorMessage(e.getMessage());
                outbox.setUpdatedAt(LocalDateTime.now());
                if (outbox.getRetryCount() >= 5) {
                    outbox.setStatus(FAILED);
                }
                outboxMapper.updateById(outbox);
                log.error("Outbox消息发送失败: id={}", outbox.getId(), e);
            }
        }
    }
}
