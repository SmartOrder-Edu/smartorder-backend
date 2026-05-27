package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GenerateQuestionVOTest {

    @Test
    void buildsFrontendResponseFromGeneratedQuestions() {
        GenerateQuestionDTO request = new GenerateQuestionDTO();
        request.setLearningSpaceId(1001L);
        request.setChapterId(301L);
        request.setKnowledgePointId(3001006L);
        request.setCourseName("数据结构");
        request.setTopic("图与拓扑排序");

        EvaluationQuestion singleChoice = new EvaluationQuestion();
        singleChoice.setId(1L);
        singleChoice.setQuestionCode("DS-SINGLE-001");
        singleChoice.setLearningSpaceId(1001L);
        singleChoice.setChapterId(301L);
        singleChoice.setKnowledgePointId(3001006L);
        singleChoice.setQuestionType("single_choice");
        singleChoice.setDifficulty(4);
        singleChoice.setScore(new BigDecimal("2.00"));
        singleChoice.setAbilityDimension("concept");
        singleChoice.setStem("拓扑排序适用于哪类图？");
        singleChoice.setOptionsJson(List.of(
                Map.of("label", "A", "content", "有向无环图"),
                Map.of("label", "B", "content", "任意无向图"),
                Map.of("label", "C", "content", "强连通图"),
                Map.of("label", "D", "content", "完全图")
        ));
        singleChoice.setAnswerJson(Map.of("correctOptions", List.of("A")));
        singleChoice.setExplanation("拓扑排序只适用于有向无环图。");
        singleChoice.setCommonMistakesJson(List.of(Map.of("option", "C", "reason", "混淆了强连通与有向无环")));
        singleChoice.setTagsJson(List.of("数据结构", "图与拓扑排序", "single_choice"));
        singleChoice.setGenerationSource("agent_generated");
        singleChoice.setStatus(1);
        singleChoice.setCreatedAt(LocalDateTime.of(2026, 5, 27, 18, 30));
        singleChoice.setUpdatedAt(LocalDateTime.of(2026, 5, 27, 18, 31));

        EvaluationQuestion judge = new EvaluationQuestion();
        judge.setId(2L);
        judge.setQuestionCode("DS-JUDGE-002");
        judge.setLearningSpaceId(1001L);
        judge.setQuestionType("judge");
        judge.setDifficulty(4);
        judge.setScore(new BigDecimal("1.00"));
        judge.setAbilityDimension("analysis");
        judge.setStem("存在环的有向图也可以完成拓扑排序。");
        judge.setOptionsJson(List.of());
        judge.setAnswerJson(Map.of("correctValue", false));
        judge.setExplanation("有向图存在环时无法得到完整拓扑序列。");
        judge.setCommonMistakesJson(List.of(Map.of("reason", "忽略了拓扑排序对无环条件的要求")));
        judge.setTagsJson(List.of("数据结构", "图与拓扑排序", "judge"));
        judge.setGenerationSource("agent_generated");
        judge.setStatus(1);

        GenerateQuestionVO response = GenerateQuestionVO.from(request, List.of(singleChoice, judge));

        assertEquals(1001L, response.getLearningSpaceId());
        assertEquals(301L, response.getChapterId());
        assertEquals(3001006L, response.getKnowledgePointId());
        assertEquals("数据结构", response.getCourseName());
        assertEquals("图与拓扑排序", response.getTopic());
        assertEquals(2, response.getTotalQuestionCount());
        assertEquals(new BigDecimal("3.00"), response.getTotalScore());
        assertNotNull(response.getGeneratedAt());

        assertEquals(2, response.getQuestionTypeSummaryList().size());
        assertEquals("single_choice", response.getQuestionTypeSummaryList().get(0).getQuestionType());
        assertEquals("单选题", response.getQuestionTypeSummaryList().get(0).getQuestionTypeName());
        assertEquals(1, response.getQuestionTypeSummaryList().get(0).getCount());
        assertEquals(new BigDecimal("2.00"), response.getQuestionTypeSummaryList().get(0).getScore());

        assertEquals(2, response.getQuestions().size());
        GenerateQuestionVO.QuestionVO first = response.getQuestions().get(0);
        assertEquals("DS-SINGLE-001", first.getQuestionCode());
        assertEquals("单选题", first.getQuestionTypeName());
        assertEquals("拓扑排序适用于哪类图？", first.getStem());
        assertEquals(4, first.getOptionsJson().size());
        assertEquals(Map.of("correctOptions", List.of("A")), first.getAnswerJson());
        assertEquals(List.of("A"), first.getStandardAnswer());
        assertEquals("拓扑排序只适用于有向无环图。", first.getExplanation());

        GenerateQuestionVO.QuestionVO second = response.getQuestions().get(1);
        assertEquals("判断题", second.getQuestionTypeName());
        assertEquals(false, second.getStandardAnswer());
        assertEquals(List.of(), second.getOptionsJson());
    }
}
