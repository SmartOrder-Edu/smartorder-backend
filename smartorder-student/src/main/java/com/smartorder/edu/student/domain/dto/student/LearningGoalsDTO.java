package com.smartorder.edu.student.domain.dto.student;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学习目标维度 DTO
 * <p>对应 JSON Schema: learning_goals.json</p>
 * <p>包含主目标、目标集合、可度量标准及进度</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LearningGoalsDTO {

    /** 主目标ID */
    private String primaryGoalId;

    /** 目标集合，key 为目标ID */
    private Map<String, GoalDTO> goals;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 学习目标详情
     */
    @Data
    public static class GoalDTO {
        private String goalId;
        /** 目标范围：SUBJECT / CHAPTER / GLOBAL */
        private String scope;
        /** 关联科目ID */
        private String subjectId;
        private String title;
        private String description;
        /** 目标类型：COURSE_MASTERY / EXAM_PREPARATION / ... */
        private String goalType;
        /** 目标水平：PROFICIENT / MASTER / ... */
        private String targetLevel;
        /** 目标知识点ID列表 */
        private List<String> targetKnowledgePointIds;
        /** 可度量标准列表 */
        private List<MeasurableCriteriaDTO> measurableCriteria;
        /** 优先级：HIGH / MEDIUM / LOW */
        private String priority;
        /** 状态：IN_PROGRESS / COMPLETED / PAUSED / ... */
        private String status;
        /** 开始时间 */
        private String startAt;
        /** 截止时间 */
        private String deadline;
        /** 每周时间预算（分钟） */
        private Integer weeklyTimeBudgetMinutes;
        /** 进度 */
        private ProgressDTO progress;
        private Double confidence;
        private List<String> evidenceIds;
        private String createdAt;
        private String updatedAt;
    }

    /**
     * 可度量标准
     */
    @Data
    public static class MeasurableCriteriaDTO {
        /** 度量指标：OVERALL_MASTERY_SCORE / EXERCISE_ACCURACY_RATE / ... */
        private String metric;
        /** 比较运算符：GREATER_THAN_OR_EQUAL / LESS_THAN_OR_EQUAL / ... */
        private String operator;
        /** 目标值 */
        private Object targetValue;
    }

    /**
     * 目标进度
     */
    @Data
    public static class ProgressDTO {
        /** 进度百分比 */
        private Double progressPercent;
        /** 已完成标准数 */
        private Integer completedCriteriaCount;
        /** 总标准数 */
        private Integer totalCriteriaCount;
        /** 计算方式：SYSTEM_DERIVED / USER_REPORTED */
        private String calculationMethod;
    }
}
