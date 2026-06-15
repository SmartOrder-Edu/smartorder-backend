package com.smartorder.edu.student.domain.dto.student;

import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学习偏好维度 DTO
 * <p>对应 JSON Schema: learning_preference.json</p>
 * <p>包含资源偏好、讲解偏好、练习偏好、反馈偏好、交互偏好及科目覆盖</p>
 *
 * @author 谢光湘
 * @since 2026-06-15
 */
@Data
public class LearningPreferenceDTO {

    /** 资源类型偏好，key 为资源类型 */
    private Map<String, ResourcePreferenceDTO> resourcePreferences;

    /** 讲解偏好 */
    private ExplanationPreferencesDTO explanationPreferences;

    /** 练习偏好 */
    private PracticePreferencesDTO practicePreferences;

    /** 反馈偏好 */
    private FeedbackPreferencesDTO feedbackPreferences;

    /** 交互偏好 */
    private InteractionPreferencesDTO interactionPreferences;

    /** 科目级偏好覆盖，key 为科目ID */
    private Map<String, SubjectOverrideDTO> subjectOverrides;

    /** 维度元数据 */
    private DimensionMetadataDTO metadata;

    // ====== 内部 DTO ======

    /**
     * 资源类型偏好详情
     */
    @Data
    public static class ResourcePreferenceDTO {
        private String resourceType;
        /** 声明偏好：HIGH / MEDIUM / LOW */
        private String declaredPreference;
        /** 观测有效性得分 0~100 */
        private Integer observedEffectivenessScore;
        /** 选择率 0~1 */
        private Double selectionRate;
        /** 完成率 0~1 */
        private Double completionRate;
        /** 学习后正确率 0~1 */
        private Double postLearningAccuracyRate;
        private Double confidence;
        private List<String> evidenceIds;
    }

    /**
     * 讲解偏好
     */
    @Data
    public static class ExplanationPreferencesDTO {
        /** 偏好顺序：EXAMPLE_THEN_PRINCIPLE / PRINCIPLE_THEN_EXAMPLE */
        private String preferredSequence;
        /** 内容粒度：FINE / MEDIUM / COARSE */
        private String contentGranularity;
        /** 偏好表征方式列表 */
        private List<String> preferredRepresentation;
        /** 偏好语言风格 */
        private String preferredLanguageStyle;
        /** 类比偏好：MODERATE / HIGH / LOW */
        private String analogyPreference;
    }

    /**
     * 练习偏好
     */
    @Data
    public static class PracticePreferencesDTO {
        /** 练习时机：LEARN_THEN_IMMEDIATE_PRACTICE / ... */
        private String practiceTiming;
        /** 偏好题型列表 */
        private List<String> preferredQuestionTypes;
        /** 难度递进方式：GRADUAL / IMMEDIATE_CHALLENGE */
        private String preferredDifficultyProgression;
        /** 每次练习题数 */
        private Integer preferredQuestionsPerSession;
        /** 是否允许提示 */
        private Boolean allowHints;
        /** 偏好提示风格 */
        private String preferredHintStyle;
    }

    /**
     * 反馈偏好
     */
    @Data
    public static class FeedbackPreferencesDTO {
        /** 反馈时机：IMMEDIATE / DELAYED */
        private String feedbackTiming;
        /** 反馈详细程度：DETAILED / BRIEF */
        private String feedbackDetailLevel;
        /** 是否立即显示正确答案 */
        private Boolean showCorrectAnswerImmediately;
        /** 是否显示错误原因 */
        private Boolean showErrorCause;
        /** 是否显示改进建议 */
        private Boolean showImprovementSuggestion;
    }

    /**
     * 交互偏好
     */
    @Data
    public static class InteractionPreferencesDTO {
        /** 偏好交互模式：GUIDED_DIALOGUE / FREE_EXPLORATION */
        private String preferredInteractionMode;
        /** 是否允许智能体主动提醒 */
        private Boolean allowAgentProactiveReminder;
        /** 偏好提醒频率：MODERATE / HIGH / LOW */
        private String preferredReminderFrequency;
        /** 偏好提问风格：SOCRATIC_WITH_HINT / DIRECT */
        private String preferredQuestioningStyle;
    }

    /**
     * 科目级偏好覆盖
     */
    @Data
    public static class SubjectOverrideDTO {
        /** 偏好资源类型列表 */
        private List<String> preferredResourceTypes;
        /** 偏好讲解顺序 */
        private String preferredExplanationSequence;
        /** 覆盖原因 */
        private String reason;
        private Double confidence;
        private List<String> evidenceIds;
    }
}
