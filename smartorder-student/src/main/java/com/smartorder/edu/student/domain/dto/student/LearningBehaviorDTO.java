package com.smartorder.edu.student.domain.dto.student;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学习行为维度 DTO（科目级）
 * <p>对应 JSON Schema: learning_behavior.json</p>
 * <p>包含时间窗口统计、行为模式、资源有效性、趋势及参与度</p>
 *
 * @author 谢光湘
 * @since 2026-06-15
 */
@Data
public class LearningBehaviorDTO {

    /** 时间窗口统计，key 为窗口名：LAST_7_DAYS / LAST_30_DAYS */
    private Map<String, StatisticsWindowDTO> statisticsWindows;

    /** 行为模式 */
    private BehaviorPatternsDTO behaviorPatterns;

    /** 资源有效性，key 为资源类型 */
    private Map<String, ResourceEffectivenessDTO> resourceEffectiveness;

    /** 趋势 */
    private TrendsDTO trends;

    /** 参与度 */
    private EngagementDTO engagement;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 时间窗口统计
     */
    @Data
    public static class StatisticsWindowDTO {
        private String windowStart;
        private String windowEnd;
        /** 活跃天数 */
        private Integer activeDays;
        /** 学习时长（分钟） */
        private Integer studyMinutes;
        /** 学习会话数 */
        private Integer sessionCount;
        /** 平均会话时长（分钟） */
        private Integer averageSessionMinutes;
        /** 资源查看数 */
        private Integer resourceViewCount;
        /** 资源完成率 0~1 */
        private Double resourceCompletionRate;
        /** 练习尝试数 */
        private Integer exerciseAttemptCount;
        /** 练习正确率 0~1 */
        private Double exerciseAccuracyRate;
        /** 独立正确率 0~1 */
        private Double independentAccuracyRate;
        /** 提示使用率 0~1 */
        private Double hintUsageRate;
        /** 复习次数 */
        private Integer reviewCount;
        /** 活跃提问数 */
        private Integer activeQuestionCount;
        /** 任务放弃率 0~1 */
        private Double taskAbandonmentRate;
    }

    /**
     * 行为模式
     */
    @Data
    public static class BehaviorPatternsDTO {
        /** 偏好学习时段 */
        private String preferredStudyPeriod;
        /** 平均会话时长（分钟） */
        private Integer averageSessionMinutes;
        /** 学习规律性得分 0~100 */
        private Integer studyRegularnessScore;
        /** 持续性得分 0~100 */
        private Integer persistenceScore;
        /** 复习规律性得分 0~100 */
        private Integer reviewRegularnessScore;
        /** 拖延风险：HIGH / MEDIUM / LOW */
        private String procrastinationRisk;
        /** 提示依赖度：HIGH / MEDIUM / LOW */
        private String hintDependency;
        /** 快速猜测风险：HIGH / MEDIUM / LOW */
        private String rapidGuessingRisk;
    }

    /**
     * 资源有效性
     */
    @Data
    public static class ResourceEffectivenessDTO {
        private Integer viewCount;
        /** 完成率 0~1 */
        private Double completionRate;
        /** 平均参与度得分 0~100 */
        private Integer averageEngagementScore;
        /** 学习后正确率 0~1 */
        private Double postLearningAccuracyRate;
    }

    /**
     * 趋势
     */
    @Data
    public static class TrendsDTO {
        /** 学习时长趋势 */
        private String studyDurationTrend;
        /** 正确率趋势 */
        private String accuracyTrend;
        /** 独立完成趋势 */
        private String independentCompletionTrend;
        /** 提示使用趋势 */
        private String hintUsageTrend;
    }

    /**
     * 参与度
     */
    @Data
    public static class EngagementDTO {
        /** 参与度得分 0~100 */
        private Integer engagementScore;
        /** 参与度等级：HIGH / MEDIUM / LOW */
        private String engagementLevel;
        /** 风险标记列表 */
        private List<String> riskFlags;
    }
}
