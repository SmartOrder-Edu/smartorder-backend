package com.smartorder.edu.student.listener;

import com.smartorder.edu.common.event.UserRegisteredEvent;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;

/**
 * 监听用户注册事件，始终创建初始（空）学生画像，待引导页补全
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
        profile.setConfidenceScore(BigDecimal.ZERO);
        profile.setLastUpdateReason("注册创建，待引导页初始化");

        // 8 个维度均初始化为空 Map
        profile.setBasicProfile(new HashMap<>());
        profile.setKnowledgeProfile(new HashMap<>());
        profile.setAbilityProfile(new HashMap<>());
        profile.setPreferenceProfile(new HashMap<>());
        profile.setCognitiveProfile(new HashMap<>());
        profile.setWeaknessProfile(new HashMap<>());
        profile.setBehaviorProfile(new HashMap<>());
        profile.setEmotionProfile(new HashMap<>());

        studentProfileService.saveOrUpdate(profile);
    }
}
