package com.smartorder.edu.student.service.impl;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationQuestionServiceImplTest {

    @Test
    void generatesQuestionsWithDefaultScoresAndPersistsThem() {
        EvaluationQuestionMapper mapper = mock(EvaluationQuestionMapper.class);
        when(mapper.insert(any(EvaluationQuestion.class))).thenReturn(1);
        EvaluationQuestionServiceImpl service = new EvaluationQuestionServiceImpl(mapper);
        GenerateQuestionDTO request = new GenerateQuestionDTO();
        request.setLearningSpaceId(1001L);
        request.setChapterId(301L);
        request.setKnowledgePointId(3001006L);
        request.setCourseName("数据结构");
        request.setTopic("图与拓扑排序");
        request.setQuestionTypes(List.of("single_choice", "scenario"));
        request.setDifficulty(3);
        request.setCount(3);

        List<EvaluationQuestion> questions = service.generateQuestions(request);

        assertEquals(3, questions.size());
        ArgumentCaptor<EvaluationQuestion> captor = ArgumentCaptor.forClass(EvaluationQuestion.class);
        verify(mapper, times(3)).insert(captor.capture());
        List<EvaluationQuestion> saved = captor.getAllValues();
        assertEquals(new BigDecimal("2.00"), saved.get(0).getScore());
        assertEquals(new BigDecimal("10.00"), saved.get(1).getScore());
        assertEquals(new BigDecimal("2.00"), saved.get(2).getScore());
        assertEquals(1001L, saved.get(0).getLearningSpaceId());
        assertEquals(301L, saved.get(0).getChapterId());
        assertEquals(3001006L, saved.get(0).getKnowledgePointId());
        assertEquals("agent_generated", saved.get(0).getGenerationSource());
        assertEquals(1, saved.get(0).getStatus());
        assertNotNull(saved.get(0).getQuestionCode());
        assertFalse(saved.get(0).getQuestionCode().isBlank());
        assertTrue(saved.get(0).getStem().contains("数据结构"));
        assertTrue(saved.get(0).getStem().contains("图与拓扑排序"));
        assertNotNull(saved.get(0).getAnswerJson());
        assertNotNull(saved.get(0).getCommonMistakesJson());
    }
}
