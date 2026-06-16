package com.smartorder.edu.student.domain.dto.student;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 当前学习状态维度 DTO（科目级，瞬时态）
 * <p>对应 JSON Schema: current_learning_state.json</p>
 * <p>包含学习状态、当前任务、阻塞问题、会话信号、最近表现、就绪度及下一步行动</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentLearningStateDTO {

    /** 状态ID */
    private String stateId;

    /** 学习状态：FLOW / STUCK / FRUSTRATED / DISENGAGED / ... */
    private String learningStatus;

    /** 当前目标ID */
    private String currentGoalId;

    /** 当前计划节点ID */
    private String currentPlanNodeId;

    /** 当前章节ID */
    private String currentChapterId;

    /** 当前知识点ID */
    private String currentKnowledgePointId;

    /** 当前任务 */
    private CurrentTaskDTO currentTask;

    /** 阻塞问题，key 为问题ID */
    private Map<String, BlockingIssueDTO> blockingIssues;

    /** 会话信号 */
    private SessionSignalsDTO sessionSignals;

    /** 最近表现 */
    private RecentPerformanceDTO recentPerformance;

    /** 就绪度 */
    private ReadinessDTO readiness;

    /** 下一步行动列表 */
    private List<NextActionDTO> nextActions;

    /** 有效期开始 */
    private String validFrom;

    /** 有效期结束 */
    private String validUntil;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 当前任务
     */
    @Data
    public static class CurrentTaskDTO {
        private String taskId;
        /** 任务类型：CODE_IMPLEMENTATION / QUIZ / ... */
        private String taskType;
        private String title;
        /** 难度：EASY / MEDIUM / HARD */
        private String difficulty;
        private String startedAt;
        /** 尝试次数 */
        private Integer attemptCount;
        /** 使用提示次数 */
        private Integer hintCount;
    }

    /**
     * 阻塞问题
     */
    @Data
    public static class BlockingIssueDTO {
        private String issueId;
        /** 问题类型：PROCEDURE_ORDER_ERROR / CONCEPT_CONFUSION / ... */
        private String issueType;
        private String description;
        /** 严重程度：HIGH / MEDIUM / LOW */
        private String severity;
        /** 状态：ACTIVE / RESOLVED */
        private String status;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 会话信号
     */
    @Data
    public static class SessionSignalsDTO {
        /** 参与度：HIGH / MEDIUM / LOW */
        private String engagementLevel;
        /** 认知负荷：HIGH / MEDIUM / LOW */
        private String cognitiveLoad;
        /** 困惑程度：HIGH / MEDIUM / LOW */
        private String confusionLevel;
        /** 挫败感信号 */
        private FrustrationSignalDTO frustrationSignal;
    }

    /**
     * 挫败感信号
     */
    @Data
    public static class FrustrationSignalDTO {
        /** 挫败程度：HIGH / MEDIUM / LOW */
        private String value;
        /** 信号来源：USER_EXPLICIT / SYSTEM_INFERRED */
        private String source;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 最近表现
     */
    @Data
    public static class RecentPerformanceDTO {
        private Integer recentAttemptCount;
        /** 最近正确率 0~1 */
        private Double recentAccuracyRate;
        /** 连续错误数 */
        private Integer consecutiveWrongCount;
        /** 平均响应时间（秒） */
        private Integer averageResponseSeconds;
        /** 提示使用次数 */
        private Integer hintUsageCount;
    }

    /**
     * 就绪度
     */
    @Data
    public static class ReadinessDTO {
        /** 是否建议继续当前任务 */
        private Boolean continueCurrentTask;
        /** 是否需要补救 */
        private Boolean needRemediation;
        /** 推荐难度：EASY / MEDIUM / HARD */
        private String recommendedDifficulty;
        /** 推荐支持级别：GUIDED / PROMPTED / INDEPENDENT */
        private String recommendedSupportLevel;
    }

    /**
     * 下一步行动
     */
    @Data
    public static class NextActionDTO {
        /** 顺序 */
        private Integer order;
        /** 行动类型：VIEW_DIAGRAM / TRACE_CODE / PRACTICE / ... */
        private String actionType;
        private String title;
        private String knowledgePointId;
        /** 预估时长（分钟） */
        private Integer estimatedMinutes;
        /** 推荐原因 */
        private String reason;
    }
}
