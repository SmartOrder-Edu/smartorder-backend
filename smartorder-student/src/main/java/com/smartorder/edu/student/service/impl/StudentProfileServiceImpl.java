package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.dto.ProfileInitDTO;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * @author 谢光湘
 * @since 2026-05-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentProfileServiceImpl implements IStudentProfileService {

    private final StudentProfileMapper studentProfileMapper;

    @Override
    public StudentProfile getByUserId(Long userId) {
        QueryWrapper<StudentProfile> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
          .isNull("subject_id")
          .last("LIMIT 1");
        return studentProfileMapper.selectOne(qw);
    }

    @Override
    public StudentProfile getByUserIdAndSubject(Long userId, Long subjectId) {
        QueryWrapper<StudentProfile> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (subjectId != null) {
            qw.eq("subject_id", subjectId);
        } else {
            qw.isNull("subject_id");
        }
        qw.last("LIMIT 1");
        return studentProfileMapper.selectOne(qw);
    }

    @Override
    public void saveOrUpdate(StudentProfile profile) {
        if (profile.getId() == null) {
            studentProfileMapper.insert(profile);
            log.info("创建学生画像: userId={}, id={}", profile.getUserId(), profile.getId());
        } else {
            studentProfileMapper.updateById(profile);
            log.info("更新学生画像: userId={}, id={}", profile.getUserId(), profile.getId());
        }
    }

    @Override
    public void initProfile(Long userId, ProfileInitDTO dto) {
        StudentProfile profile = getByUserId(userId);
        if (profile == null) {
            // 注册时没创建画像（兼容老用户），新建一个
            profile = new StudentProfile();
            profile.setUserId(userId);
        }

        // 构建 basicProfile JSON
        Map<String, Object> basic = new HashMap<>();
        if (dto.getIdentity() != null && !dto.getIdentity().isEmpty()) {
            basic.put("identity_type", dto.getIdentity());
        }
        if (dto.getGrade() != null && !dto.getGrade().isEmpty()) {
            basic.put("grade", dto.getGrade());
        }
        if (dto.getDirection() != null && !dto.getDirection().isEmpty()) {
            basic.put("target_direction", dto.getDirection());
        }
        if (dto.getDirectionDetail() != null && !dto.getDirectionDetail().isBlank()) {
            basic.put("target_direction_detail", dto.getDirectionDetail());
        }
        if (dto.getLevel() != null && !dto.getLevel().isEmpty()) {
            basic.put("current_level", dto.getLevel());
        }
        if (dto.getLevelDetail() != null && !dto.getLevelDetail().isBlank()) {
            basic.put("current_level_detail", dto.getLevelDetail());
        }
        if (dto.getGoal() != null && !dto.getGoal().isEmpty()) {
            basic.put("primary_goal", dto.getGoal());
        }
        if (dto.getGoalDetail() != null && !dto.getGoalDetail().isBlank()) {
            basic.put("primary_goal_detail", dto.getGoalDetail());
        }
        if (dto.getGoalDeadline() != null && !dto.getGoalDeadline().isEmpty()) {
            basic.put("goal_deadline", dto.getGoalDeadline());
        }
        if (dto.getDailyStudyTime() != null && !dto.getDailyStudyTime().isEmpty()) {
            basic.put("daily_study_time", dto.getDailyStudyTime());
        }
        if (dto.getSelfDescription() != null && !dto.getSelfDescription().isBlank()) {
            basic.put("self_description", dto.getSelfDescription());
        }

        profile.setBasicProfile(basic);
        profile.setConfidenceScore(new BigDecimal("30.0"));
        profile.setProfileVersion(profile.getProfileVersion() == null ? 1 : profile.getProfileVersion() + 1);
        profile.setLastUpdateReason("引导页初始化");

        saveOrUpdate(profile);
        log.info("引导页画像初始化完成: userId={}", userId);
    }
}
