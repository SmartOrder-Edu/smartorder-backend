package com.smartorder.edu.manage.listener;

import com.smartorder.edu.manage.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 反馈提交事件监听器：
 * 采用 TransactionalEventListener 在事务安全提交后（AFTER_COMMIT）被触发，
 * 配合 @Async 实现后台多线程异步 AI 初审，从架构上消除网络延迟，防止脏读，保证数据 100% 安全入库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackEventListener {

    private final FeedbackService feedbackService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFeedbackSubmitted(FeedbackSubmittedEvent event) {
        Long feedbackId = event.getFeedbackId();
        log.info(">>>>>> 监听到反馈提交事件 [事务已提交]，开始多线程异步 AI 审核任务. feedbackId={} <<<<<<", feedbackId);
        try {
            feedbackService.asyncAiReview(feedbackId);
        } catch (Exception e) {
            log.error("异步 AI 审核执行过程中发生未捕获异常, feedbackId={}", feedbackId, e);
        }
    }
}
