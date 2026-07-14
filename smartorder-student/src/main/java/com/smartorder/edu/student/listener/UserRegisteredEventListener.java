package com.smartorder.edu.student.listener;

import com.smartorder.edu.common.event.UserRegisteredEvent;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 注册后创建空学生画像，后续由引导对话、测评和学习行为持续补全。
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredEventListener {

    private final IStudentProfileService studentProfileService;

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        log.info("为用户 {} 创建初始空学生画像", event.getUserId());

        StudentProfile profile = new StudentProfile();
        profile.setUserId(event.getUserId());
        profile.setProfileVersion(0);
        profile.setProfileConfidenceScore(new BigDecimal("0.00"));
        profile.setLastUpdateReason("注册创建，待引导页初始化");

        studentProfileService.saveOrUpdate(profile);
    }
}
