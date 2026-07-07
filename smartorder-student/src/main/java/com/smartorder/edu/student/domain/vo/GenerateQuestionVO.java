package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class GenerateQuestionVO {

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 章节ID */
    private Long chapterId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 课程名称 */
    private String courseName;

    /** 出题主题 */
    private String topic;

    /** 生成题目总数 */
    private Integer totalQuestionCount;

    /** 生成题目总分 */
    private BigDecimal totalScore;

    /** 生成时间 */
    private LocalDateTime generatedAt;

    /** 题型统计 */
    private List<QuestionTypeSummaryVO> questionTypeSummaryList;

    /** 题目列表 */
    private List<QuestionVO> questions;

    /**
     * 将「生成请求 + 已生成题目列表」聚合成对外展示视图。
     *
     * <p>计算总题数、总分，并按题型维度汇总数量与分值，便于前端展示题库概览。</p>
     */
    public static GenerateQuestionVO from(GenerateQuestionDTO dto, List<EvaluationQuestion> generatedQuestions) {
        List<EvaluationQuestion> questionList = generatedQuestions == null ? List.of() : generatedQuestions;

        GenerateQuestionVO vo = new GenerateQuestionVO();
        vo.setLearningSpaceId(dto.getLearningSpaceId());
        vo.setChapterId(dto.getChapterId());
        vo.setKnowledgePointId(dto.getKnowledgePointId());
        vo.setCourseName(dto.getCourseName());
        vo.setTopic(dto.getTopic());
        vo.setTotalQuestionCount(questionList.size());
        vo.setGeneratedAt(LocalDateTime.now());

        BigDecimal totalScore = BigDecimal.ZERO;
        Map<String, QuestionTypeSummaryVO> summaryMap = new LinkedHashMap<>();
        List<QuestionVO> questionVOList = new ArrayList<>();

        for (EvaluationQuestion question : questionList) {
            BigDecimal score = question.getScore() == null ? BigDecimal.ZERO : question.getScore();
            totalScore = totalScore.add(score);
            questionVOList.add(QuestionVO.from(question));

            String questionType = question.getQuestionType();
            // 按题型聚合：首次出现则创建汇总项，否则累加数量与分值
            QuestionTypeSummaryVO summary = summaryMap.computeIfAbsent(questionType, type -> {
                QuestionTypeSummaryVO item = new QuestionTypeSummaryVO();
                item.setQuestionType(type);
                item.setQuestionTypeName(resolveQuestionTypeName(type));
                item.setCount(0);
                item.setScore(BigDecimal.ZERO);
                return item;
            });
            summary.setCount(summary.getCount() + 1);
            summary.setScore(summary.getScore().add(score));
        }

        vo.setTotalScore(totalScore);
        vo.setQuestionTypeSummaryList(new ArrayList<>(summaryMap.values()));
        vo.setQuestions(questionVOList);
        return vo;
    }

    /** 题型编码 → 中文名映射（如 single_choice → 单选题） */
    private static String resolveQuestionTypeName(String questionType) {
        if (questionType == null) {
            return null;
        }
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
     * 从标准答案 JSON 中提取一个友好的展示值。
     *
     * <p>不同题型答案结构不同：选择题取 correctOptions、判断题/填空取 correctValue、
     * 填空题取 blanks、其余取 answer；字段缺失时回退整个 JSON 对象。</p>
     */
    private static Object resolveStandardAnswer(Map<String, Object> answerJson) {
        if (answerJson == null || answerJson.isEmpty()) {
            return null;
        }
        if (answerJson.containsKey("correctOptions")) {
            return answerJson.get("correctOptions");
        }
        if (answerJson.containsKey("correctValue")) {
            return answerJson.get("correctValue");
        }
        if (answerJson.containsKey("blanks")) {
            return answerJson.get("blanks");
        }
        if (answerJson.containsKey("answer")) {
            return answerJson.get("answer");
        }
        return answerJson;
    }

    @Data
    public static class QuestionTypeSummaryVO {
        /** 题型编码 */
        private String questionType;

        /** 题型名称 */
        private String questionTypeName;

        /** 该题型数量 */
        private Integer count;

        /** 该题型总分 */
        private BigDecimal score;
    }

    @Data
    public static class QuestionVO {
        /** 题目ID */
        private Long id;

        /** 题目唯一编码 */
        private String questionCode;

        /** 学习空间ID */
        private Long learningSpaceId;

        /** 章节ID */
        private Long chapterId;

        /** 知识点ID */
        private Long knowledgePointId;

        /** 题型编码 */
        private String questionType;

        /** 题型名称 */
        private String questionTypeName;

        /** 难度 */
        private Integer difficulty;

        /** 分值 */
        private BigDecimal score;

        /** 能力维度 */
        private String abilityDimension;

        /** 题干 */
        private String stem;

        /** 选项JSON，选择题为 A/B/C/D，填空题和判断题通常为空数组 */
        private List<Map<String, Object>> optionsJson;

        /** 标准答案JSON原始结构 */
        private Map<String, Object> answerJson;

        /** 标准答案展示值，按题型从 answerJson 中提取 */
        private Object standardAnswer;

        /** 题目解析 */
        private String explanation;

        /** 常见错误分析 */
        private List<Map<String, Object>> commonMistakesJson;

        /** 标签 */
        private List<String> tagsJson;

        /** 知识点标签 */
        private List<String> knowledgePoints;

        /** 能力标签 */
        private List<String> abilityTags;

        /** 题目分析 */
        private Map<String, Object> analysis;

        /** 生成来源 */
        private String generationSource;

        /** 状态 */
        private Integer status;

        /** 创建时间 */
        private LocalDateTime createdAt;

        /** 更新时间 */
        private LocalDateTime updatedAt;

        public static QuestionVO from(EvaluationQuestion question) {
            QuestionVO vo = new QuestionVO();
            vo.setId(question.getId());
            vo.setQuestionCode(question.getQuestionCode());
            vo.setLearningSpaceId(question.getLearningSpaceId());
            vo.setChapterId(question.getChapterId());
            vo.setKnowledgePointId(question.getKnowledgePointId());
            vo.setQuestionType(question.getQuestionType());
            vo.setQuestionTypeName(resolveQuestionTypeName(question.getQuestionType()));
            vo.setDifficulty(question.getDifficulty());
            vo.setScore(question.getScore());
            vo.setAbilityDimension(question.getAbilityDimension());
            vo.setStem(question.getStem());
            vo.setOptionsJson(question.getOptionsJson());
            vo.setAnswerJson(question.getAnswerJson());
            vo.setStandardAnswer(resolveStandardAnswer(question.getAnswerJson()));
            vo.setExplanation(question.getExplanation());
            vo.setCommonMistakesJson(question.getCommonMistakesJson());
            vo.setTagsJson(question.getTagsJson());
            vo.setKnowledgePoints(question.getKnowledgePointsJson());
            vo.setAbilityTags(question.getAbilityTagsJson());
            vo.setAnalysis(question.getAnalysisJson());
            vo.setGenerationSource(question.getGenerationSource());
            vo.setStatus(question.getStatus());
            vo.setCreatedAt(question.getCreatedAt());
            vo.setUpdatedAt(question.getUpdatedAt());
            return vo;
        }
    }
}
