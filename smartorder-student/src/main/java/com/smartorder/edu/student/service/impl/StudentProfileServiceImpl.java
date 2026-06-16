package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileDimensionUpdateDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileUpdateDTO;
import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import com.smartorder.edu.student.domain.dto.student.CommonMisconceptionsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningBackgroundDTO;
import com.smartorder.edu.student.domain.dto.student.LearningGoalsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningPreferenceDTO;
import com.smartorder.edu.student.domain.dto.student.MetacognitiveProfileDTO;
import com.smartorder.edu.student.domain.dto.student.dto.SubjectProfilesDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 学生动态学习画像服务。
 *
 * <p>Controller 和 Agent 只提交请求 DTO，持久化实体仅在服务内部使用。画像维度在写入前
 * 会转换成对应的强类型 DTO，避免直接把任意 JSON 写入实体。</p>
 */
@Slf4j
@Service
public class StudentProfileServiceImpl implements IStudentProfileService {

    private static final BigDecimal INITIAL_CONFIDENCE = new BigDecimal("30.00");
    private static final int PROFILE_DIMENSION_COUNT = 7;
    private static final int MAX_REASON_LENGTH = 500;
    private static final String DEFAULT_UPDATE_REASON = "更新学生画像";
    private static final Set<String> SUPPORTED_DIMENSIONS = Set.of(
            "learning_background",
            "learning_goals",
            "ability_profile",
            "common_misconceptions",
            "learning_preference",
            "metacognitive_profile",
            "subject_profiles"
    );

    private final StudentProfileMapper studentProfileMapper;
    private final ObjectMapper objectMapper;
    private final ObjectMapper snakeCaseObjectMapper;

    /** 构造函数，注入Mapper和ObjectMapper，初始化snake_case版本的ObjectMapper */
    public StudentProfileServiceImpl(StudentProfileMapper studentProfileMapper, ObjectMapper objectMapper) {
        this.studentProfileMapper = studentProfileMapper;
        this.objectMapper = objectMapper;
        this.snakeCaseObjectMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /** 根据用户ID查询画像，不存在则返回 null */
    @Override
    public StudentProfile getByUserId(Long userId) {
        validateUserId(userId);
        LambdaQueryWrapper<StudentProfile> query = new LambdaQueryWrapper<>();
        query.eq(StudentProfile::getUserId, userId);
        return studentProfileMapper.selectOne(query);
    }

    /** 保存或更新画像，已存在则跳过重复创建 */
    @Override
    @Transactional
    public void saveOrUpdate(StudentProfile profile) {
        if (profile == null) {
            throw new BusinessException("学生画像不能为空");
        }
        validateUserId(profile.getUserId());

        if (profile.getId() == null && getByUserId(profile.getUserId()) != null) {
            log.debug("学生画像已存在，跳过重复创建: userId={}", profile.getUserId());
            return;
        }
        prepareForPersistence(profile);
        persist(profile);
    }

    /** 用户主动更新画像，支持部分维度更新，至少提交一个维度 */
    @Override
    @Transactional
    public StudentProfile updateProfile(Long userId, StudentProfileUpdateDTO patch) {
        validateUserId(userId);
        if (patch == null) {
            throw new BusinessException("学生画像更新内容不能为空");
        }

        StudentProfile profile = getOrCreateProfile(userId);
        int updatedDimensionCount = 0;

        if (patch.getLearningBackground() != null) {
            profile.setLearningBackground(mergeTyped(
                    profile.getLearningBackground(),
                    patch.getLearningBackground(),
                    LearningBackgroundDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getLearningGoals() != null) {
            profile.setLearningGoals(mergeTyped(
                    profile.getLearningGoals(),
                    patch.getLearningGoals(),
                    LearningGoalsDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getAbilityProfile() != null) {
            profile.setAbilityProfile(mergeTyped(
                    profile.getAbilityProfile(),
                    patch.getAbilityProfile(),
                    AbilityProfileDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getCommonMisconceptions() != null) {
            profile.setCommonMisconceptions(mergeTyped(
                    profile.getCommonMisconceptions(),
                    patch.getCommonMisconceptions(),
                    CommonMisconceptionsDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getLearningPreference() != null) {
            profile.setLearningPreference(mergeTyped(
                    profile.getLearningPreference(),
                    patch.getLearningPreference(),
                    LearningPreferenceDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getMetacognitiveProfile() != null) {
            profile.setMetacognitiveProfile(mergeTyped(
                    profile.getMetacognitiveProfile(),
                    patch.getMetacognitiveProfile(),
                    MetacognitiveProfileDTO.class
            ));
            updatedDimensionCount++;
        }
        if (patch.getSubjectProfiles() != null) {
            profile.setSubjectProfiles(mergeSubjectProfilesDTO(
                    profile.getSubjectProfiles(),
                    patch.getSubjectProfiles()
            ));
            updatedDimensionCount++;
        }

        if (updatedDimensionCount == 0) {
            throw new BusinessException("至少需要提交一个画像维度");
        }

        return completeUpdate(profile, "用户主动更新画像", null);
    }

    /** 引导页初始化画像，根据用户填写的基础信息创建画像 */
    @Override
    @Transactional
    public StudentProfile initProfile(Long userId, ProfileInitDTO dto) {
        validateUserId(userId);
        if (dto == null) {
            throw new BusinessException("画像初始化内容不能为空");
        }

        LearningBackgroundDTO background = buildInitialBackground(dto);
        LearningGoalsDTO goals = buildInitialGoals(dto);
        if (background == null && goals == null) {
            throw new BusinessException("画像初始化内容不能全部为空");
        }

        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setProfileVersion(0);
        profile.setProfileCompleteness(new BigDecimal("0.00"));
        profile.setProfileConfidenceScore(new BigDecimal("0.00"));

        if (background != null) {
            profile.setLearningBackground(mergeTyped(
                    profile.getLearningBackground(),
                    background,
                    LearningBackgroundDTO.class
            ));
        }
        if (goals != null) {
            profile.setLearningGoals(mergeTyped(
                    profile.getLearningGoals(),
                    goals,
                    LearningGoalsDTO.class
            ));
        }

        return completeUpdate(profile, "引导页初始化画像", INITIAL_CONFIDENCE);
    }

    /** 更新单个画像维度，通过增量数据合并 */
    @Override
    @Transactional
    public StudentProfile updateProfileDimension(Long userId, StudentProfileDimensionUpdateDTO update) {
        if (update == null) {
            throw new BusinessException("画像维度更新内容不能为空");
        }
        return applyDimensionUpdates(userId, List.of(update));
    }

    /** 批量更新多个画像维度 */
    @Override
    @Transactional
    public void updateProfileDimensions(
            Long userId,
            List<StudentProfileDimensionUpdateDTO> dimensionUpdates
    ) {
        applyDimensionUpdates(userId, dimensionUpdates);
    }

    /** 根据用户ID删除画像 */
    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        validateUserId(userId);
        StudentProfile profile = getByUserId(userId);
        if (profile == null) {
            throw new BusinessException("未找到该用户的画像");
        }
        studentProfileMapper.deleteById(profile.getId());
        log.info("删除学生画像: userId={}, id={}", userId, profile.getId());
    }

    /** 应用维度更新列表，合并增量数据并保存 */
    private StudentProfile applyDimensionUpdates(
            Long userId,
            List<StudentProfileDimensionUpdateDTO> dimensionUpdates
    ) {
        validateUserId(userId);
        List<StudentProfileDimensionUpdateDTO> updates = validateDimensionUpdates(dimensionUpdates);
        StudentProfile profile = getOrCreateProfile(userId);
        StringJoiner reasonJoiner = new StringJoiner("; ");

        for (StudentProfileDimensionUpdateDTO update : updates) {
            mergeDimension(profile, update);
            reasonJoiner.add(update.getReason());
        }

        StudentProfile result = completeUpdate(profile, reasonJoiner.toString(), null);
        log.info(
                "学生画像维度更新完成: userId={}, version={}, dimensions={}",
                userId,
                result.getProfileVersion(),
                updates.stream().map(StudentProfileDimensionUpdateDTO::getDimensionKey).toList()
        );
        return result;
    }

    /** 根据维度key将增量数据合并到对应字段 */
    private void mergeDimension(StudentProfile profile, StudentProfileDimensionUpdateDTO update) {
        Map<String, Object> delta = update.getDelta();
        switch (update.getDimensionKey()) {
            case "learning_background" -> profile.setLearningBackground(mergeDelta(
                    profile.getLearningBackground(),
                    delta,
                    LearningBackgroundDTO.class
            ));
            case "learning_goals" -> profile.setLearningGoals(mergeDelta(
                    profile.getLearningGoals(),
                    delta,
                    LearningGoalsDTO.class
            ));
            case "ability_profile" -> profile.setAbilityProfile(mergeDelta(
                    profile.getAbilityProfile(),
                    delta,
                    AbilityProfileDTO.class
            ));
            case "common_misconceptions" -> profile.setCommonMisconceptions(mergeDelta(
                    profile.getCommonMisconceptions(),
                    delta,
                    CommonMisconceptionsDTO.class
            ));
            case "learning_preference" -> profile.setLearningPreference(mergeDelta(
                    profile.getLearningPreference(),
                    delta,
                    LearningPreferenceDTO.class
            ));
            case "metacognitive_profile" -> profile.setMetacognitiveProfile(mergeDelta(
                    profile.getMetacognitiveProfile(),
                    delta,
                    MetacognitiveProfileDTO.class
            ));
            case "subject_profiles" -> profile.setSubjectProfiles(mergeSubjectProfilesDelta(
                    profile.getSubjectProfiles(),
                    delta
            ));
            default -> throw new BusinessException("不支持的画像维度: " + update.getDimensionKey());
        }
    }

    /** 校验维度更新列表，确保维度名称有效且增量数据不为空 */
    private List<StudentProfileDimensionUpdateDTO> validateDimensionUpdates(
            List<StudentProfileDimensionUpdateDTO> dimensionUpdates
    ) {
        if (dimensionUpdates == null || dimensionUpdates.isEmpty()) {
            throw new BusinessException("画像维度更新列表不能为空");
        }

        List<StudentProfileDimensionUpdateDTO> validated = new ArrayList<>(dimensionUpdates.size());
        for (StudentProfileDimensionUpdateDTO update : dimensionUpdates) {
            if (update == null) {
                throw new BusinessException("画像维度更新不能包含空项");
            }

            String dimensionKey = normalizeDimensionKey(update.getDimensionKey());
            if (!SUPPORTED_DIMENSIONS.contains(dimensionKey)) {
                throw new BusinessException("不支持的画像维度: " + dimensionKey);
            }
            if (update.getDelta() == null || update.getDelta().isEmpty()) {
                throw new BusinessException("画像维度增量数据不能为空: " + dimensionKey);
            }

            String reason = update.getReason();
            if (reason == null || reason.isBlank()) {
                reason = DEFAULT_UPDATE_REASON;
            } else {
                reason = reason.trim();
            }
            validated.add(new StudentProfileDimensionUpdateDTO(dimensionKey, update.getDelta(), reason));
        }
        return validated;
    }

    /** 递增版本号，设置更新原因,摘要，重新计算完整度后持久化 */
    private StudentProfile completeUpdate(
            StudentProfile profile,
            String reason,
            BigDecimal minimumConfidence
    ) {
        profile.setProfileVersion(currentVersion(profile) + 1);
        profile.setLastUpdateReason(limitReason(reason));
        profile.setProfileSummary(null);

        if (minimumConfidence != null && (profile.getProfileConfidenceScore() == null
                || profile.getProfileConfidenceScore().compareTo(minimumConfidence) < 0)) {
            profile.setProfileConfidenceScore(minimumConfidence);
        }

        prepareForPersistence(profile);
        persist(profile);
        return profile;
    }

    /** 获取用户画像，不存在则创建空画像对象（未持久化） */
    private StudentProfile getOrCreateProfile(Long userId) {
        StudentProfile profile = getByUserId(userId);
        if (profile != null) {
            return profile;
        }

        StudentProfile created = new StudentProfile();
        created.setUserId(userId);
        created.setProfileVersion(0);
        created.setProfileCompleteness(new BigDecimal("0.00"));
        created.setProfileConfidenceScore(new BigDecimal("0.00"));
        return created;
    }

    /** 从引导页DTO构建学习背景，无有效数据则返回 null */
    private LearningBackgroundDTO buildInitialBackground(ProfileInitDTO dto) {
        boolean hasBackground = hasText(dto.getIdentity())
                || hasText(dto.getGrade())
                || hasText(dto.getDirection())
                || hasText(dto.getDirectionDetail())
                || hasText(dto.getLevel())
                || hasText(dto.getLevelDetail())
                || hasText(dto.getDailyStudyTime())
                || hasText(dto.getSelfDescription());
        if (!hasBackground) {
            return null;
        }

        LearningBackgroundDTO background = new LearningBackgroundDTO();
        if (hasText(dto.getIdentity())
                || hasText(dto.getGrade())
                || hasText(dto.getDirection())
                || hasText(dto.getDirectionDetail())) {
            LearningBackgroundDTO.EducationDTO education = new LearningBackgroundDTO.EducationDTO();
            education.setEducationLevel(toEnumValue(dto.getIdentity()));
            education.setGrade(toEnumValue(dto.getGrade()));
            education.setMajorCode(toEnumValue(dto.getDirection()));
            education.setMajorName(trimToNull(dto.getDirectionDetail()));
            background.setEducation(education);
        }

        if (hasText(dto.getLevel())) {
            LearningBackgroundDTO.PrerequisiteFoundationDTO baseline =
                    new LearningBackgroundDTO.PrerequisiteFoundationDTO();
            baseline.setStatus(toFoundationStatus(dto.getLevel()));
            baseline.setScore(toFoundationScore(dto.getLevel()));

            LearningBackgroundDTO.PriorExperienceDTO priorExperience =
                    new LearningBackgroundDTO.PriorExperienceDTO();
            priorExperience.setPrerequisiteFoundation(Map.of("SELF_REPORTED_BASELINE", baseline));
            background.setPriorExperience(priorExperience);
        }

        Integer weeklyMinutes = toWeeklyMinutes(dto.getDailyStudyTime());
        if (weeklyMinutes != null) {
            LearningBackgroundDTO.LearningConstraintsDTO constraints =
                    new LearningBackgroundDTO.LearningConstraintsDTO();
            constraints.setWeeklyAvailableMinutes(weeklyMinutes);
            background.setLearningConstraints(constraints);
        }

        background.setCurrentLevelDetail(trimToNull(dto.getLevelDetail()));
        background.setSelfDescription(trimToNull(dto.getSelfDescription()));
        return background;
    }

    /** 从引导页DTO构建学习目标，无有效数据则返回 null */
    private LearningGoalsDTO buildInitialGoals(ProfileInitDTO dto) {
        boolean hasGoal = hasText(dto.getGoal())
                || hasText(dto.getGoalDetail())
                || hasText(dto.getGoalDeadline());
        if (!hasGoal) {
            return null;
        }

        String goalId = "ONBOARDING_PRIMARY";
        LearningGoalsDTO.GoalDTO goal = new LearningGoalsDTO.GoalDTO();
        goal.setGoalId(goalId);
        goal.setScope("GLOBAL");
        goal.setGoalType(hasText(dto.getGoal()) ? toEnumValue(dto.getGoal()) : "GENERAL");
        goal.setTitle(resolveGoalTitle(dto));
        goal.setDescription(trimToNull(dto.getGoalDetail()));
        goal.setDeadline(trimToNull(dto.getGoalDeadline()));
        goal.setWeeklyTimeBudgetMinutes(toWeeklyMinutes(dto.getDailyStudyTime()));
        goal.setStatus("ACTIVE");

        Map<String, LearningGoalsDTO.GoalDTO> goals = new LinkedHashMap<>();
        goals.put(goalId, goal);

        LearningGoalsDTO result = new LearningGoalsDTO();
        result.setPrimaryGoalId(goalId);
        result.setGoals(goals);
        return result;
    }

    /** 将Map格式的增量数据转换为强类型后合并 */
    private <T> T mergeDelta(T current, Map<String, Object> delta, Class<T> targetType) {
        try {
            T typedPatch = snakeCaseObjectMapper.convertValue(delta, targetType);
            return mergeTyped(current, typedPatch, targetType);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("画像维度数据格式不正确: " + exception.getMessage());
        }
    }

    /** 深度合并两个同类型对象，patch中的非空字段覆盖current */
    private <T> T mergeTyped(T current, T patch, Class<T> targetType) {
        try {
            ObjectNode targetNode = toObjectNode(current);
            JsonNode patchNode = objectMapper.valueToTree(patch);
            mergeJson(targetNode, patchNode);
            return objectMapper.convertValue(targetNode, targetType);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("画像维度数据格式不正确: " + exception.getMessage());
        }
    }

    /** 将Map格式的科目画像增量数据转换后合并 */
    private SubjectProfilesDTO mergeSubjectProfilesDelta(
            SubjectProfilesDTO current,
            Map<String, Object> delta
    ) {
        try {
            SubjectProfilesDTO typedPatch = snakeCaseObjectMapper.convertValue(delta, SubjectProfilesDTO.class);
            return mergeSubjectProfilesDTO(current, typedPatch);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("科目画像数据格式不正确: " + exception.getMessage());
        }
    }

    /** 深度合并两个科目画像对象 */
    private SubjectProfilesDTO mergeSubjectProfilesDTO(
            SubjectProfilesDTO current,
            SubjectProfilesDTO patch
    ) {
        try {
            ObjectNode targetNode = toObjectNode(current);
            mergeJson(targetNode, objectMapper.valueToTree(patch));
            return objectMapper.convertValue(targetNode, SubjectProfilesDTO.class);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("科目画像数据格式不正确: " + exception.getMessage());
        }
    }

    /** 将对象转换为Jackson ObjectNode，null则创建空节点 */
    private ObjectNode toObjectNode(Object value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = objectMapper.valueToTree(value);
        if (!node.isObject()) {
            throw new BusinessException("画像维度必须是 JSON 对象");
        }
        return (ObjectNode) node;
    }

    /** 递归合并JSON对象，patch中的非空字段覆盖target */
    private void mergeJson(ObjectNode target, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return;
        }

        ObjectNode patchObj = (ObjectNode) patch;
        patchObj.fieldNames().forEachRemaining(key -> {
            JsonNode patchValue = patchObj.get(key);
            if (patchValue == null || patchValue.isNull()) {
                return;
            }

            JsonNode currentValue = target.get(key);
            if (patchValue.isObject() && currentValue != null && currentValue.isObject()) {
                mergeJson((ObjectNode) currentValue, patchValue);
            } else {
                target.set(key, patchValue.deepCopy());
            }
        });
    }

    /** 持久化前的预处理：初始化默认值，精度处理，重算完整度 */
    private void prepareForPersistence(StudentProfile profile) {
        if (profile.getProfileConfidenceScore() == null) {
            profile.setProfileConfidenceScore(new BigDecimal("0.00"));
        } else {
            profile.setProfileConfidenceScore(
                    profile.getProfileConfidenceScore().setScale(2, RoundingMode.HALF_UP)
            );
        }
        recalculateCompleteness(profile);
    }

    /** 重新计算画像完整度，7个维度中已填写的数量占比 */
    private void recalculateCompleteness(StudentProfile profile) {
        int completed = 0;
        completed += isMeaningful(profile.getLearningBackground()) ? 1 : 0;
        completed += isMeaningful(profile.getLearningGoals()) ? 1 : 0;
        completed += isMeaningful(profile.getAbilityProfile()) ? 1 : 0;
        completed += isMeaningful(profile.getCommonMisconceptions()) ? 1 : 0;
        completed += isMeaningful(profile.getLearningPreference()) ? 1 : 0;
        completed += isMeaningful(profile.getMetacognitiveProfile()) ? 1 : 0;
        completed += isMeaningful(profile.getSubjectProfiles()) ? 1 : 0;

        BigDecimal completeness = BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(PROFILE_DIMENSION_COUNT), 2, RoundingMode.HALF_UP);
        profile.setProfileCompleteness(completeness);
    }

    /** 判断对象是否包含有意义的数据（非null且非空） */
    private boolean isMeaningful(Object value) {
        if (value == null) {
            return false;
        }
        return hasMeaningfulValue(objectMapper.valueToTree(value));
    }

    /** 递归判断JSON节点是否包含有意义的值 */
    private boolean hasMeaningfulValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isValueNode()) {
            return !node.isTextual() || !node.textValue().isBlank();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasMeaningfulValue(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                if (hasMeaningfulValue(node.get(fieldNames.next()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 执行数据库持久化，新增或更新 */
    private void persist(StudentProfile profile) {
        int affectedRows = profile.getId() == null
                ? studentProfileMapper.insert(profile)
                : studentProfileMapper.updateById(profile);
        if (affectedRows != 1) {
            throw new BusinessException("学生画像保存失败，请稍后重试");
        }
        log.info("保存学生画像: userId={}, id={}, version={}",
                profile.getUserId(), profile.getId(), profile.getProfileVersion());
    }

    /** 校验用户ID有效性 */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("用户ID无效");
        }
    }

    /** 标准化维度名称：trim并转小写 */
    private String normalizeDimensionKey(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            throw new BusinessException("画像维度名称不能为空");
        }
        return dimensionKey.trim().toLowerCase(Locale.ROOT);
    }

    /** 有画像版本,则返回画像版本, 没有画像版本，则返回 0 */
    private int currentVersion(StudentProfile profile) {
        return profile.getProfileVersion() == null ? 0 : profile.getProfileVersion();
    }

    /** 更新原因长度限制在 500 字以内，超过部分会被截断。理由不能为空或全空白，否则会使用默认理由。 */
    private String limitReason(String reason) {
        String value = reason == null || reason.isBlank() ? DEFAULT_UPDATE_REASON : reason.trim();
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }

    /** 判断字符串是否有有效内容 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** trim后返回，空白字符串返回null */
    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /** 解析学习目标标题，优先使用详细描述，其次使用目标类型 */
    private String resolveGoalTitle(ProfileInitDTO dto) {
        if (hasText(dto.getGoalDetail())) {
            return dto.getGoalDetail().trim();
        }
        if (hasText(dto.getGoal())) {
            return dto.getGoal().trim();
        }
        return "待完善的学习目标";
    }

    /** 转换为枚举格式：trim、空格和连字符转下划线、大写 */
    private String toEnumValue(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    /** 将用户自评水平转换为基础知识满足状态 */
    private String toFoundationStatus(String level) {
        return switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "advanced", "course" -> "SATISFIED";
            case "some" -> "PARTIALLY_SATISFIED";
            default -> "NOT_SATISFIED";
        };
    }

    /** 将用户自评水平转换为基础知识分数 */
    private Integer toFoundationScore(String level) {
        return switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "advanced" -> 85;
            case "course" -> 65;
            case "some" -> 45;
            default -> 20;
        };
    }

    /** 将每日学习时间选项转换为每周可用分钟数 */
    private Integer toWeeklyMinutes(String dailyStudyTime) {
        if (!hasText(dailyStudyTime)) {
            return null;
        }
        return switch (dailyStudyTime.trim().toLowerCase(Locale.ROOT)) {
            case "lt30" -> 210;
            case "30to60" -> 315;
            case "1to2" -> 630;
            case "2to3" -> 1050;
            case "gt3" -> 1470;
            default -> null;
        };
    }
}
