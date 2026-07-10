package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评题库视图对象 —— 对应 evaluation_question 表所有字段
 *
 * @author maq
 * @since 2026-07-07
 */
@Data
public class EvaluationQuestionVO {

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

    /** 标签JSON */
    private List<String> tags;

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
     * 从实体转换为 VO
     */
    public static EvaluationQuestionVO from(EvaluationQuestion q) {
        EvaluationQuestionVO vo = new EvaluationQuestionVO();
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
        vo.setTags(q.getTags());
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

    /**
     * 题型编码 → 中文名映射
     */
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

    /**
     * 从标准答案 JSON 中提取友好的展示值
     */
    private static Object resolveStandardAnswer(Map<String, Object> answerJson) {
        if (answerJson == null || answerJson.isEmpty()) return null;
        if (answerJson.containsKey("correctOptions")) return answerJson.get("correctOptions");
        if (answerJson.containsKey("correctValue")) return answerJson.get("correctValue");
        if (answerJson.containsKey("blanks")) return answerJson.get("blanks");
        if (answerJson.containsKey("answer")) return answerJson.get("answer");
        return answerJson;
    }
}
