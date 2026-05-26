package com.smartorder.edu.manage.listener;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 反馈提交成功事件（支持异步/事务后处理）
 */
@Getter
public class FeedbackSubmittedEvent extends ApplicationEvent {

    private final Long feedbackId;

    public FeedbackSubmittedEvent(Object source, Long feedbackId) {
        super(source);
        this.feedbackId = feedbackId;
    }
}
