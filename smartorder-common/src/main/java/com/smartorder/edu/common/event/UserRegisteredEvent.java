package com.smartorder.edu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户注册成功事件 — 携带画像初始化数据，供下游模块创建 StudentProfile
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Getter
public class UserRegisteredEvent extends ApplicationEvent {

    private final Long userId;
    private final String identity;
    private final String direction;
    private final String directionDetail;
    private final String level;
    private final String levelDetail;
    private final String goal;
    private final String goalDetail;
    private final String selfDescription;

    public UserRegisteredEvent(Object source,
                               Long userId,
                               String identity,
                               String direction,
                               String directionDetail,
                               String level,
                               String levelDetail,
                               String goal,
                               String goalDetail,
                               String selfDescription) {
        super(source);
        this.userId = userId;
        this.identity = identity;
        this.direction = direction;
        this.directionDetail = directionDetail;
        this.level = level;
        this.levelDetail = levelDetail;
        this.goal = goal;
        this.goalDetail = goalDetail;
        this.selfDescription = selfDescription;
    }

    /**
     * 是否有任何画像数据
     */
    public boolean hasProfileData() {
        return identity != null || direction != null || level != null
                || goal != null || (selfDescription != null && !selfDescription.isBlank())
                || (directionDetail != null && !directionDetail.isBlank())
                || (levelDetail != null && !levelDetail.isBlank())
                || (goalDetail != null && !goalDetail.isBlank());
    }
}
