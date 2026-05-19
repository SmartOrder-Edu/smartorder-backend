package com.smartorder.edu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户注册成功事件 — 下游模块根据此事件创建初始 StudentProfile
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Getter
public class UserRegisteredEvent extends ApplicationEvent {

    private final Long userId;

    public UserRegisteredEvent(Object source, Long userId) {
        super(source);
        this.userId = userId;
    }
}
