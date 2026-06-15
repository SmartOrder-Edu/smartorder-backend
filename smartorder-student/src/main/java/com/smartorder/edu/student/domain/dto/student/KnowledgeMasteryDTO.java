package com.smartorder.edu.student.domain.dto.student;

import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 知识掌握度维度 DTO（科目级）
 * <p>对应 JSON Schema: knowledge_mastery.json</p>
 * <p>包含章节摘要、知识点详情、认知掌握度、保持力、子级易错模式</p>
 *
 * @author 谢光湘
 * @since 2026-06-15
 */
@Data
public class KnowledgeMasteryDTO {

    /** 综合掌握度得分 */
    private Double overallMasteryScore;

    /** 综合掌握度等级：BEGINNER / DEVELOPING / PROFICIENT / ADVANCED */
    private String overallMasteryLevel;

    /** 知识点覆盖率 0~1 */
    private Double coverageRate;

    /** 趋势：IMPROVING / STABLE / DECLINING */
    private String trend;

    /** 当前学习章节ID */
    private String currentChapterId;

    /** 章节摘要，key 为章节ID */
    private Map<String, ChapterSummaryDTO> chapterSummaries;

    /** 知识点详情，key 为知识点ID */
    private Map<String, KnowledgePointDTO> knowledgePoints;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 章节摘要
     */
    @Data
    public static class ChapterSummaryDTO {
        private String chapterId;
        private String chapterName;
        /** 掌握度得分 0~100 */
        private Integer masteryScore;
        private String masteryLevel;
        /** 已完成知识点数 */
        private Integer completedKnowledgePointCount;
        /** 知识点总数 */
        private Integer totalKnowledgePointCount;
        /** 薄弱知识点ID列表 */
        private List<String> weakKnowledgePointIds;
    }

    /**
     * 知识点详情
     */
    @Data
    public static class KnowledgePointDTO {
        private String knowledgePointId;
        /** 知识点名称缓存 */
        private String knowledgePointNameCache;
        private String chapterId;
        /** 知识类型：CONCEPT / PROCEDURE_AND_IMPLEMENTATION / ... */
        private String knowledgeType;
        /** 重要性：HIGH / MEDIUM / LOW */
        private String importance;
        /** 前置知识点ID列表 */
        private List<String> prerequisiteKnowledgePointIds;
        /** 掌握度得分 0~100 */
        private Double masteryScore;
        private String masteryLevel;
        /** 认知掌握度（布鲁姆分类法） */
        private CognitiveMasteryDTO cognitiveMastery;
        /** 独立完成水平：INDEPENDENT / WITH_HINT / NEEDS_GUIDANCE */
        private String independenceLevel;
        /** 前置知识就绪度 */
        private PrerequisiteReadinessDTO prerequisiteReadiness;
        /** 评估统计 */
        private AssessmentStatisticsDTO assessmentStatistics;
        /** 保持力 */
        private RetentionDTO retention;
        /** 知识点级易错模式，key 为易错ID */
        private Map<String, PointMisconceptionDTO> misconceptions;
        private Double confidence;
        private List<String> evidenceIds;
        private String firstLearnedAt;
        private String lastUpdatedAt;
    }

    /**
     * 认知掌握度（布鲁姆分类法）
     */
    @Data
    public static class CognitiveMasteryDTO {
        /** 记忆 0~100 */
        private Integer remember;
        /** 理解 0~100 */
        private Integer understand;
        /** 应用 0~100 */
        private Integer apply;
        /** 分析 0~100 */
        private Integer analyze;
        /** 实现 0~100 */
        private Integer implement;
    }

    /**
     * 前置知识就绪度
     */
    @Data
    public static class PrerequisiteReadinessDTO {
        /** 状态：READY / PARTIALLY_READY / NOT_READY */
        private String status;
        /** 未满足的前置知识点ID列表 */
        private List<String> unsatisfiedKnowledgePointIds;
    }

    /**
     * 评估统计
     */
    @Data
    public static class AssessmentStatisticsDTO {
        private Integer attemptCount;
        private Integer correctCount;
        private Integer partialCorrectCount;
        private Integer wrongCount;
        /** 正确率 0~1 */
        private Double accuracyRate;
        /** 独立正确率 0~1 */
        private Double independentCorrectRate;
        /** 平均响应时间（秒） */
        private Integer averageResponseSeconds;
        /** 最高完成难度 */
        private String highestCompletedDifficulty;
    }

    /**
     * 保持力
     */
    @Data
    public static class RetentionDTO {
        private String lastLearnedAt;
        private String lastPracticedAt;
        private String nextReviewAt;
        /** 遗忘风险：HIGH / MEDIUM / LOW */
        private String forgettingRisk;
        /** 保持力得分 0~100 */
        private Integer retentionScore;
    }

    /**
     * 知识点级易错模式
     */
    @Data
    public static class PointMisconceptionDTO {
        private String misconceptionId;
        private String title;
        /** 状态：ACTIVE / RESOLVED */
        private String status;
        /** 严重程度：HIGH / MEDIUM / LOW */
        private String severity;
        private Double confidence;
        private List<String> evidenceIds;
    }
}
