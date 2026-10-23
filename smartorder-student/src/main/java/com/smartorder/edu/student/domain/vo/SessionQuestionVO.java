package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 试卷题目视图对象 —— 用于 {@code /evaluation/session/{id}/questions} 接口
 *
 * <p>相比 {@link EvaluationQuestionVO}，该类去掉了 tags 字段，因为前端答题页不需要渲染标签。</p>
 *
 * @author maq
 * @since 2026-07-14
 */
@Data
public class SessionQuestionVO {

    /** 题目ID */
    private Long id;

    /** 题目唯一编码 */
    private String questionCode;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 章节ID */
    private Long chapterId;

    /** 题型编码 */
    private String questionType;

    /** 题型中文名 */
    private String questionTypeName;

    /** 排序序号（同题型内排序，数值越小越靠前） */
    private Integer sortOrder;

    /** 难度等级：1-5 */
    private Integer difficulty;

    /** 题目分值 */
    private BigDecimal score;

    /** 能力维度：concept/application/analysis/design/debug */
    private String abilityDimension;

    /** 题干 */
    private String stem;

    /** 选项JSON */
    private List<Map<String, Object>> options;

    /** 标准答案JSON */
    private Map<String, Object> correctAnswer;

    /** 标准答案展示值（从 correctAnswer 按题型提取） */
    private Object standardAnswer;

    /** 题目解析 */
    private String explanation;

    /** 常见错误分析JSON */
    private List<Map<String, Object>> commonMistakes;

    /** 知识点标签 */
    private List<String> knowledgePoints;

    /** 能力标签 */
    private List<String> abilityTags;

    /** 题目分析（correct_reason + wrong_reason） */
    private Map<String, Object> analysis;

    /** 来源：agent_generated/manual/rag/imported */
    private String generationSource;

    /** 状态：1启用，0禁用 */
    private Integer status;

    /** 审核状态 */
    private String reviewStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 从实体转换为试卷题目 VO（不含 tags 字段）
     */
    public static SessionQuestionVO from(EvaluationQuestion q) {
        SessionQuestionVO vo = new SessionQuestionVO();
        vo.setId(q.getId());
        vo.setQuestionCode(q.getQuestionCode());
        vo.setLearningSpaceId(q.getLearningSpaceId());
        vo.setChapterId(q.getChapterId());
        vo.setQuestionType(q.getQuestionType());
        vo.setQuestionTypeName(resolveQuestionTypeName(q.getQuestionType()));
        vo.setSortOrder(q.getSortOrder());
        vo.setDifficulty(q.getDifficulty());
        vo.setScore(q.getScore());
        vo.setAbilityDimension(q.getAbilityDimension());
        vo.setStem(q.getStem());
        vo.setOptions(q.getOptions());
        vo.setCorrectAnswer(q.getCorrectAnswer());
        vo.setStandardAnswer(resolveStandardAnswer(q.getCorrectAnswer()));
        vo.setExplanation(q.getExplanation());
        vo.setCommonMistakes(q.getCommonMistakes());
        vo.setKnowledgePoints(q.getKnowledgePoints());
        vo.setAbilityTags(q.getAbilityTagsJson());
        vo.setAnalysis(q.getAnalysisJson());
        vo.setGenerationSource(q.getGenerationSource());
        vo.setStatus(q.getStatus());
        vo.setReviewStatus(q.getReviewStatus());
        vo.setCreatedAt(q.getCreatedAt());
        vo.setUpdatedAt(q.getUpdatedAt());
        return vo;
    }

    private static String resolveQuestionTypeName(String questionType) {
        if (questionType == null) return null;
        return switch (questionType) {
            case "single_choice" -> "单选题";
            case "multiple_choice" -> "多选题";
            case "fill_blank" -> "填空题";
            case "judge" -> "判断题";
            case "short_answer" -> "简答题";
            case "code" -> "编程题";
            case "scenario" -> "场景题";
            case "sql" -> "SQL题";
            case "multimodal" -> "多模态题";
            default -> questionType;
        };
    }

    private static Object resolveStandardAnswer(Map<String, Object> answerJson) {
        if (answerJson == null || answerJson.isEmpty()) return null;
        if (answerJson.containsKey("correctOptions")) return answerJson.get("correctOptions");
        if (answerJson.containsKey("correctValue")) return answerJson.get("correctValue");
        if (answerJson.containsKey("blanks")) return answerJson.get("blanks");
        if (answerJson.containsKey("answer")) return answerJson.get("answer");
        return answerJson;
    }
}
