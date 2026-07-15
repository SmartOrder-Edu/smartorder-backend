package com.smartorder.edu.student.domain.dto.student;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 综合能力维度 DTO
 * <p>对应 JSON Schema: ability_profile.json</p>
 * <p>包含知识理解力、问题分析力、方案设计力、实践应用力、纠错反思力和迁移创新力</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbilityProfileDTO {

    /** 综合能力总分 */
    private Double overallScore;

    /** 综合能力等级：BEGINNER / DEVELOPING / PROFICIENT / ADVANCED */
    private String overallLevel;

    /** 各能力项，key 为能力代码 */
    private Map<String, AbilityItemDTO> abilities;

    /** 优势能力代码列表 */
    private List<String> strengthAbilityCodes;

    /** 优先发展能力代码列表 */
    private List<String> developmentPriorityCodes;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 单项能力详情
     */
    @Data
    public static class AbilityItemDTO {
        private String abilityCode;
        private String abilityName;
        /** 能力得分 0~100 */
        private Integer score;
        /** 能力等级：BEGINNER / DEVELOPING / PROFICIENT / ADVANCED */
        private String level;
        /** 趋势：IMPROVING / STABLE / DECLINING */
        private String trend;
        /** 优势总结 */
        private String strengthSummary;
        /** 改进重点 */
        private String improvementFocus;
        private Double confidence;
        private List<String> evidenceIds;
        /** 最近评估时间 */
        private String lastEvaluatedAt;
    }
}
