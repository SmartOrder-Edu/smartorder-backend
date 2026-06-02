package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public void updateProfileDimension(Long userId, String dimensionKey, Map<String, Object> delta, String reason) {
        StudentProfile profile = getByUserId(userId);
        if (profile == null) {
            // 画像不存在，创建新画像
            profile = new StudentProfile();
            profile.setUserId(userId);
            profile.setProfileVersion(0);
            profile.setConfidenceScore(BigDecimal.ZERO);
            initEmptyDimensions(profile);
        }

        // 将 snake_case 维度名转为 camelCase 字段名
        String fieldName = toCamelCase(dimensionKey);

        // 通过反射获取对应维度的 Map 字段
        Map<String, Object> existing = getDimensionValue(profile, fieldName);
        if (existing == null) {
            existing = new HashMap<>();
            setDimensionValue(profile, fieldName, existing);
        }

        // 深度合并 delta
        deepMerge(existing, delta);

        // 递增版本、记录原因
        profile.setProfileVersion(
                (profile.getProfileVersion() == null ? 0 : profile.getProfileVersion()) + 1);
        profile.setLastUpdateReason(reason);

        saveOrUpdate(profile);
        log.info("画像维度增量更新: userId={}, dimension={}, reason={}", userId, dimensionKey, reason);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateProfileDimensions(Long userId, List<DimensionUpdateDTO> dimensionUpdates) {
        if (dimensionUpdates == null || dimensionUpdates.isEmpty()) return;

        // 只加载一次画像
        StudentProfile profile = getByUserId(userId);
        if (profile == null) {
            profile = new StudentProfile();
            profile.setUserId(userId);
            profile.setProfileVersion(0);
            profile.setConfidenceScore(BigDecimal.ZERO);
            initEmptyDimensions(profile);
        }

        // 依次合并每个维度
        StringBuilder reasonBuilder = new StringBuilder();
        for (DimensionUpdateDTO update : dimensionUpdates) {
            String dimensionKey = update.dimensionKey();
            Map<String, Object> delta = update.delta();
            if (dimensionKey == null || dimensionKey.isBlank() || delta == null || delta.isEmpty()) continue;

            String fieldName = toCamelCase(dimensionKey);
            Map<String, Object> existing = getDimensionValue(profile, fieldName);
            if (existing == null) {
                existing = new HashMap<>();
                setDimensionValue(profile, fieldName, existing);
            }
            deepMerge(existing, delta);

            if (reasonBuilder.length() > 0) reasonBuilder.append("; ");
            reasonBuilder.append(update.reason() != null ? update.reason() : dimensionKey);
        }

        // 版本号只递增一次
        profile.setProfileVersion(
                (profile.getProfileVersion() == null ? 0 : profile.getProfileVersion()) + 1);
        profile.setLastUpdateReason(reasonBuilder.toString());

        saveOrUpdate(profile);
        log.info("画像批量更新完成: userId={}, dimensions={}, reason={}",
                userId, dimensionUpdates.size(), reasonBuilder);
    }

    // ============ 私有工具方法 ============

    /** snake_case → camelCase "basic_profile" → "basicProfile" */
    private String toCamelCase(String snake) {
        if (snake == null) return null;
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)))
                  .append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDimensionValue(StudentProfile profile, String fieldName) {
        try {
            // 为什么用反射而不是直接调 getter？ 因为 StudentProfile 有 8 个维度字段，如果不用反射，就得写 8 个 if-else：
            Field field = StudentProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Map<String, Object>) field.get(profile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("获取画像维度字段失败: fieldName={}", fieldName, e);
            return null;
        }
    }

    private void setDimensionValue(StudentProfile profile, String fieldName, Map<String, Object> value) {
        try {
            // 为什么用反射而不是直接调 getter？ 因为 StudentProfile 有 8 个维度字段，如果不用反射，就得写 8 个 if-else：
            Field field = StudentProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(profile, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("设置画像维度字段失败: fieldName={}", fieldName, e);
        }
    }

    /**
     * 深度合并：target 中没有的 key 直接添加，已有的 key 如果双方都是 Map 则递归合并，否则用 source 覆盖
     */
    @SuppressWarnings("unchecked")// 这个 unchecked 警告是因为我们在运行时无法验证 Map<String, Object> 的类型安全，但我们在设计上保证了它的正确性
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceVal = entry.getValue();
            Object targetVal = target.get(key);

            if (sourceVal instanceof Map<?, ?> sourceMap
                    && targetVal instanceof Map<?, ?> targetMap) {
                // 双方都是 Map，递归合并
                deepMerge((Map<String, Object>) targetMap, (Map<String, Object>) sourceMap);
            } else {
                // 否则直接覆盖（包括 null、基本类型、List 等）
                target.put(key, sourceVal);
            }
        }
    }

    /** 初始化所有 8 个维度为空 Map */
    private void initEmptyDimensions(StudentProfile profile) {
        profile.setBasicProfile(new HashMap<>());
        profile.setKnowledgeProfile(new HashMap<>());
        profile.setAbilityProfile(new HashMap<>());
        profile.setPreferenceProfile(new HashMap<>());
        profile.setCognitiveProfile(new HashMap<>());
        profile.setWeaknessProfile(new HashMap<>());
        profile.setBehaviorProfile(new HashMap<>());
        profile.setEmotionProfile(new HashMap<>());
    }
}
