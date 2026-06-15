package com.smartorder.edu.student.domain.dto.student;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学习背景维度 DTO
 * <p>对应 JSON Schema: learning_background.json</p>
 * <p>包含学历、已修课程、编程经验、前置基础及学习环境约束</p>
 *
 * @author 谢光湘
 * @since 2026-06-15
 */
@Data
public class LearningBackgroundDTO {

    /** 学历信息 */
    private EducationDTO education;

    /** 先修经验 */
    private PriorExperienceDTO priorExperience;

    /** 学习约束条件 */
    private LearningConstraintsDTO learningConstraints;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 学历信息
     */
    @Data
    public static class EducationDTO {
        /** 学历层次：UNDERGRADUATE / GRADUATE / ... */
        private String educationLevel;
        /** 年级：FRESHMAN / SOPHOMORE / JUNIOR / SENIOR / ... */
        private String grade;
        /** 专业代码 */
        private String majorCode;
        /** 专业名称 */
        private String majorName;
        /** 入学年份 */
        private Integer enrollmentYear;
    }

    /**
     * 先修经验
     */
    @Data
    public static class PriorExperienceDTO {
        /** 已修课程，key 为科目ID */
        private Map<String, CompletedSubjectDTO> completedSubjects;
        /** 编程语言经验，key 为语言名 */
        private Map<String, ProgrammingLanguageDTO> programmingLanguages;
        /** 前置基础满足情况，key 为前置知识代码 */
        private Map<String, PrerequisiteFoundationDTO> prerequisiteFoundation;
    }

    /**
     * 已修课程详情
     */
    @Data
    public static class CompletedSubjectDTO {
        private String subjectId;
        private String subjectName;
        /** 完成状态：COMPLETED / IN_PROGRESS / ... */
        private String completionStatus;
        /** 自评水平：BEGINNER / INTERMEDIATE / ADVANCED */
        private String selfReportedLevel;
        /** 验证水平 */
        private String verifiedLevel;
        /** 最近学习时间 */
        private String lastStudiedAt;
        /** 置信度 0~1 */
        private Double confidence;
        /** 证据ID列表 */
        private List<String> evidenceIds;
    }

    /**
     * 编程语言经验
     */
    @Data
    public static class ProgrammingLanguageDTO {
        private String selfReportedLevel;
        private String verifiedLevel;
        /** 经验月数 */
        private Integer experienceMonths;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 前置基础满足情况
     */
    @Data
    public static class PrerequisiteFoundationDTO {
        /** 状态：SATISFIED / PARTIALLY_SATISFIED / NOT_SATISFIED */
        private String status;
        /** 得分 */
        private Integer score;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 学习约束条件
     */
    @Data
    public static class LearningConstraintsDTO {
        /** 每周可用学习时间（分钟） */
        private Integer weeklyAvailableMinutes;
        /** 偏好学习时段：EVENING / MORNING / ... */
        private List<String> preferredStudyPeriods;
        /** 单次学习最大时长（分钟） */
        private Integer maxSessionMinutes;
        /** 设备类型：PC / MOBILE / ... */
        private List<String> deviceTypes;
        /** 网络状况：STABLE / UNSTABLE */
        private String networkCondition;
        /** 无障碍需求 */
        private List<String> accessibilityNeeds;
    }
}
