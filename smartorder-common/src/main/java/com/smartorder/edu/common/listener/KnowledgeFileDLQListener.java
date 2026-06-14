package com.smartorder.edu.common.listener;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileProcessMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.smartorder.edu.common.common.Constants.FAILED;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileDLQListener {

    private final KnowledgeFileJobMapper jobMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_PROCESS_DLQ)
    public void handleDLQ(Message message) {
        String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        log.error("收到死信消息: exchange={}, routingKey={}, payload={}",
                message.getMessageProperties().getReceivedExchange(),
                message.getMessageProperties().getReceivedRoutingKey(),
                payload);

        try {
            KnowledgeFileProcessMessage msg = JSON.parseObject(payload, KnowledgeFileProcessMessage.class);
            if (msg != null && msg.getJobId() != null) {
                markJobDLQ(msg.getJobId(), "消息进入死信队列，原始payload: " + payload);
                log.error("任务已标记为DLQ失败: jobId={}, fileId={}", msg.getJobId(), msg.getFileId());
            }
        } catch (Exception e) {
            log.error("解析死信消息失败: {}", payload, e);
        }
    }

    private void markJobDLQ(Long jobId, String errorMessage) {
        try {
            var job = jobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus(FAILED);
                job.setErrorMessage(errorMessage);
                job.setFinishedAt(LocalDateTime.now());
                job.setUpdatedAt(LocalDateTime.now());
                jobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.error("更新DLQ任务状态失败: jobId={}", jobId, e);
        }
    }
}
