package com.smartorder.edu.student.domain.dto.student;

import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 元认知画像维度 DTO
 * <p>对应 JSON Schema: metacognitive_profile.json</p>
 * <p>包含学习规划、过程监控、自我评估、反思、主动求助、信心校准、策略调整、时间管理等元认知维度</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
public class MetacognitiveProfileDTO {

    /** 元认知综合得分 */
    private Double overallScore;

    /** 元认知综合等级：BEGINNER / DEVELOPING / PROFICIENT / ADVANCED */
    private String overallLevel;

    /** 各元认知维度，key 为维度代码 */
    private Map<String, MetaDimensionDTO> dimensions;

    /** 信心校准统计 */
    private CalibrationStatisticsDTO calibrationStatistics;

    /** 推荐支持策略 */
    private RecommendedSupportDTO recommendedSupport;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 元认知维度详情
     */
    @Data
    public static class MetaDimensionDTO {
        private String dimensionCode;
        private String dimensionName;
        /** 得分 0~100 */
        private Integer score;
        /** 等级：BEGINNER / DEVELOPING / PROFICIENT */
        private String level;
        /** 趋势：IMPROVING / STABLE / DECLINING */
        private String trend;
        private String description;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 信心校准统计
     */
    @Data
    public static class CalibrationStatisticsDTO {
        /** 自预测正确率 */
        private Double selfPredictedAccuracyRate;
        /** 实际正确率 */
        private Double actualAccuracyRate;
        /** 校准偏差 */
        private Double calibrationGap;
        /** 校准状态：OVERCONFIDENT / UNDERCONFIDENT / WELL_CALIBRATED */
        private String calibrationStatus;
        /** 样本数量 */
        private Integer sampleCount;
    }

    /**
     * 推荐支持策略
     */
    @Data
    public static class RecommendedSupportDTO {
        /** 规划支持级别：HIGH / MEDIUM / LOW */
        private String planningSupportLevel;
        /** 反思提示频率 */
        private String reflectionPromptFrequency;
        /** 答题前是否要求信心预测 */
        private Boolean requireConfidencePredictionBeforeAnswer;
        /** 推荐干预措施列表 */
        private List<String> recommendedInterventions;
    }
}
