package com.smartorder.edu.student.domain.dto.student;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 共性易错模式维度 DTO
 * <p>对应 JSON Schema: common_misconceptions.json</p>
 * <p>包含跨科目共性易错模式、根因假设、干预策略</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommonMisconceptionsDTO {

    /** 易错模式集合，key 为易错模式ID */
    private Map<String, MisconceptionPatternDTO> patterns;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 易错模式详情
     */
    @Data
    public static class MisconceptionPatternDTO {
        private String misconceptionId;
        private String title;
        /** 分类：BOUNDARY_CONDITION_OMISSION / PROCEDURE_ORDER_ERROR / ... */
        private String category;
        /** 作用范围：CROSS_SUBJECT / SUBJECT_SPECIFIC */
        private String scope;
        private String description;
        /** 典型症状列表 */
        private List<String> typicalSymptoms;
        /** 影响的科目ID列表 */
        private List<String> affectedSubjectIds;
        /** 影响的知识点ID列表 */
        private List<String> affectedKnowledgePointIds;
        /** 根因假设列表 */
        private List<RootCauseHypothesisDTO> rootCauseHypotheses;
        /** 严重程度：HIGH / MEDIUM / LOW */
        private String severity;
        /** 出现频率：FREQUENT / OCCASIONAL / RARE */
        private String frequency;
        /** 持续性：PERSISTENT / TRANSIENT */
        private String persistence;
        /** 状态：ACTIVE / RESOLVED / ... */
        private String status;
        /** 出现统计 */
        private OccurrenceStatisticsDTO occurrenceStatistics;
        /** 干预建议 */
        private InterventionDTO intervention;
        private Double confidence;
        private List<String> evidenceIds;
        private String updatedAt;
    }

    /**
     * 根因假设
     */
    @Data
    public static class RootCauseHypothesisDTO {
        private String causeCode;
        private String description;
        /** 概率 0~1 */
        private Double probability;
    }

    /**
     * 出现统计
     */
    @Data
    public static class OccurrenceStatisticsDTO {
        /** 最近7天出现次数 */
        private Integer countLast7Days;
        /** 最近30天出现次数 */
        private Integer countLast30Days;
        private String firstDetectedAt;
        private String lastObservedAt;
    }

    /**
     * 干预建议
     */
    @Data
    public static class InterventionDTO {
        /** 策略代码列表 */
        private List<String> strategyCodes;
        /** 建议行动列表 */
        private List<String> recommendedActions;
        /** 成功标准列表 */
        private List<SuccessCriteriaDTO> successCriteria;
    }

    /**
     * 成功标准
     */
    @Data
    public static class SuccessCriteriaDTO {
        private String metric;
        private Object targetValue;
        private String operator;
    }
}
