package com.smartorder.edu.student.domain.dto.student.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartorder.edu.student.domain.dto.student.CommonMisconceptionsDTO;
import com.smartorder.edu.student.domain.dto.student.CurrentLearningStateDTO;
import com.smartorder.edu.student.domain.dto.student.KnowledgeMasteryDTO;
import com.smartorder.edu.student.domain.dto.student.LearningBehaviorDTO;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科目画像集合 DTO
 * <p>对应 JSON Schema: sencond/subject_profiles.json</p>
 * <p>顶层为 Map 结构，key 为科目代码（如 DATA_STRUCTURES、JAVA_PROGRAMMING），
 * value 为该科目的完整画像快照，包含目标、知识掌握度、易错模式、学习偏好覆盖、行为及当前状态</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectProfilesDTO {

    /** 科目画像详情，key 为科目代码 */
    private Map<String, SubjectProfileDTO> profiles = new LinkedHashMap<>();

    // ====== 内部 DTO ======

    /**
     * 单科画像快照
     */
    @Data
    public static class SubjectProfileDTO {

        /** 科目代码 */
        private String subjectId;

        /** 科目名称缓存 */
        private String subjectNameCache;

        /** 科目状态：ACTIVE / PAUSED / ARCHIVED */
        private String subjectStatus;

        /** 画像版本号 */
        private Integer profileVersion;

        /** 科目级目标 */
        private SubjectGoalsDTO subjectGoals;

        /** 科目级知识掌握度 */
        private KnowledgeMasteryDTO knowledgeMastery;

        /** 科目级易错模式 */
        private CommonMisconceptionsDTO subjectMisconceptions;

        /** 科目级学习偏好覆盖 */
        private LearningPreferenceOverrideDTO learningPreferenceOverride;

        /** 科目级学习行为 */
        private LearningBehaviorDTO learningBehavior;

        /** 科目级当前学习状态 */
        private CurrentLearningStateDTO currentLearningState;

        /** 科目画像元数据 */
        private SubjectMetadataDTO metadata;
    }

    /**
     * 科目级目标
     */
    @Data
    public static class SubjectGoalsDTO {

        /** 关联的目标ID列表 */
        private List<String> goalIds;
    }

    /**
     * 科目级学习偏好覆盖
     */
    @Data
    public static class LearningPreferenceOverrideDTO {

        /** 偏好资源类型列表：DIAGRAM / CODE_TRACE / CODE_EXAMPLE / ... */
        private List<String> preferredResourceTypes;
    }

    /**
     * 科目画像元数据（精简版）
     */
    @Data
    public static class SubjectMetadataDTO {

        /** 置信度 0~1 */
        private Double confidence;

        /** 完整度 0~100 */
        private Integer completeness;

        /** 最近更新时间 */
        private String lastUpdatedAt;

        /** 最近评估时间 */
        private String lastAssessedAt;
    }
}
