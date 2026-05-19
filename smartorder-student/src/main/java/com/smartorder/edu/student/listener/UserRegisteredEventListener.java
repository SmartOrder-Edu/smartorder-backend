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
import java.util.Map;

/**
 * 监听用户注册事件，自动创建初始学生画像
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
        if (!event.hasProfileData()) {
            log.info("用户 {} 注册时未提供画像数据，跳过初始化", event.getUserId());
            return;
        }

        log.info("为用户 {} 创建初始学生画像", event.getUserId());

        StudentProfile profile = new StudentProfile();
        profile.setUserId(event.getUserId());
        profile.setProfileVersion(1);
        profile.setConfidenceScore(new BigDecimal("30.0"));
        profile.setLastUpdateReason("用户注册初始化");

        // 构建 basic_profile JSON
        Map<String, Object> basic = new HashMap<>();
        if (event.getIdentity() != null && !event.getIdentity().isEmpty()) {
            basic.put("identity_type", event.getIdentity());
        }
        if (event.getGrade() != null && !event.getGrade().isEmpty()) {
            basic.put("grade", event.getGrade());
        }
        if (event.getDirection() != null && !event.getDirection().isEmpty()) {
            basic.put("target_direction", event.getDirection());
        }
        if (event.getDirectionDetail() != null && !event.getDirectionDetail().isBlank()) {
            basic.put("target_direction_detail", event.getDirectionDetail());
        }
        if (event.getLevel() != null && !event.getLevel().isEmpty()) {
            basic.put("current_level", event.getLevel());
        }
        if (event.getLevelDetail() != null && !event.getLevelDetail().isBlank()) {
            basic.put("current_level_detail", event.getLevelDetail());
        }
        if (event.getGoal() != null && !event.getGoal().isEmpty()) {
            basic.put("primary_goal", event.getGoal());
        }
        if (event.getGoalDetail() != null && !event.getGoalDetail().isBlank()) {
            basic.put("primary_goal_detail", event.getGoalDetail());
        }
        if (event.getGoalDeadline() != null && !event.getGoalDeadline().isEmpty()) {
            basic.put("goal_deadline", event.getGoalDeadline());
        }
        if (event.getDailyStudyTime() != null && !event.getDailyStudyTime().isEmpty()) {
            basic.put("daily_study_time", event.getDailyStudyTime());
        }
        if (event.getSelfDescription() != null && !event.getSelfDescription().isBlank()) {
            basic.put("self_description", event.getSelfDescription());
        }

        profile.setBasicProfile(basic);
        studentProfileService.saveOrUpdate(profile);
    }
}
