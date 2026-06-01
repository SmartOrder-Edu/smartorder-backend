package com.smartorder.edu.student.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationJob;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationJobMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.util.PromptLoader;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationQuestionServiceImplTest {
    private static final Pattern COUNT_PATTERN = Pattern.compile("count=(\\d+)");

    @AfterEach
    void clearCurrentUser() {
        ThreadLocalUtils.remove();
    }

    @Test
    void createsGenerationJobAndExpandedItemsBeforeAsyncGenerationRuns() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        QuestionAiService questionAiService = mock(QuestionAiService.class);
        EvaluationQuestionMapper evaluationQuestionMapper = mock(EvaluationQuestionMapper.class);
        LearningSpaceMapper learningSpaceMapper = mock(LearningSpaceMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EvaluationQuestionGenerationJobMapper jobMapper = mock(EvaluationQuestionGenerationJobMapper.class);
        EvaluationQuestionGenerationItemMapper itemMapper = mock(EvaluationQuestionGenerationItemMapper.class);
        TransactionTemplate transactionTemplate = passthroughTransactionTemplate();

        when(jobMapper.insert(any(EvaluationQuestionGenerationJob.class))).thenAnswer(invocation -> {
            EvaluationQuestionGenerationJob job = invocation.getArgument(0);
            job.setId(101L);
            return 1;
        });
        when(itemMapper.insertBatch(any())).thenReturn(3);
        when(promptLoader.load(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.endsWith("all-evaluation-rules.txt")) {
                return """
                        {{questionTypeRules}}
                        course={{courseName}}, topic={{topic}}, count={{count}}, difficulty={{difficulty}},
                        optionCount={{optionCount}}, language={{language}},
                        programmingLanguage={{programmingLanguage}}, sqlDialect={{sqlDialect}}
                        """;
            }
            return "question type rules";
        });
        when(questionAiService.generateQuestions(anyString())).thenAnswer(invocation ->
                Flux.just(responseForPrompt(invocation.getArgument(0)))
        );

        EvaluationQuestionServiceImpl service = new EvaluationQuestionServiceImpl(
                promptLoader,
                evaluationQuestionMapper,
                learningSpaceMapper,
                objectMapper,
                jobMapper,
                itemMapper,
                transactionTemplate,
                questionAiService
        );

        GenerateQuestionDTO dto = new GenerateQuestionDTO();
        dto.setLearningSpaceId(10L);
        dto.setCourseName("Data Structures");
        dto.setTopic("Sorting");
        dto.setQuestionTypeCountList(List.of(
                typeCount("single_choice", 2, 3),
                typeCount("judge", 1, 4)
        ));
        setCurrentUserId(1L);

        service.generateQuestions(dto, new SseEmitter(1_000L));

        ArgumentCaptor<EvaluationQuestionGenerationJob> jobCaptor =
                ArgumentCaptor.forClass(EvaluationQuestionGenerationJob.class);
        verify(jobMapper).insert(jobCaptor.capture());

        EvaluationQuestionGenerationJob job = jobCaptor.getValue();
        assertNotNull(job.getJobNo());
        assertEquals(10L, job.getLearningSpaceId());
        assertEquals("Data Structures", job.getCourseName());
        assertEquals("Sorting", job.getTopic());
        assertEquals("pending", job.getStatus());
        assertEquals(3, job.getTotalCount());
        assertEquals(0, job.getSuccessCount());
        assertEquals(0, job.getFailedCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvaluationQuestionGenerationItem>> itemCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(itemMapper).insertBatch(itemCaptor.capture());

        List<EvaluationQuestionGenerationItem> items = itemCaptor.getValue();
        assertEquals(3, items.size());
        assertEquals(List.of(1, 2, 3), items.stream().map(EvaluationQuestionGenerationItem::getSequenceNo).toList());
        assertEquals(List.of("single_choice", "single_choice", "judge"),
                items.stream().map(EvaluationQuestionGenerationItem::getQuestionType).toList());
        assertEquals(List.of(3, 3, 4), items.stream().map(EvaluationQuestionGenerationItem::getDifficulty).toList());
        assertEquals(List.of("pending", "pending", "pending"),
                items.stream().map(EvaluationQuestionGenerationItem::getStatus).toList());
        assertEquals(List.of(101L, 101L, 101L),
                items.stream().map(EvaluationQuestionGenerationItem::getJobId).toList());
        verify(evaluationQuestionMapper, timeout(1_000).times(3)).insert(any(EvaluationQuestion.class));
    }

    @Test
    void startsGenerationForDifferentQuestionTypesInParallel() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        QuestionAiService questionAiService = mock(QuestionAiService.class);
        EvaluationQuestionMapper evaluationQuestionMapper = mock(EvaluationQuestionMapper.class);
        LearningSpaceMapper learningSpaceMapper = mock(LearningSpaceMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EvaluationQuestionGenerationJobMapper jobMapper = mock(EvaluationQuestionGenerationJobMapper.class);
        EvaluationQuestionGenerationItemMapper itemMapper = mock(EvaluationQuestionGenerationItemMapper.class);
        TransactionTemplate transactionTemplate = passthroughTransactionTemplate();
        AtomicInteger llmCallCount = new AtomicInteger();
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        AtomicBoolean secondStartedBeforeFirstCompleted = new AtomicBoolean(false);

        when(jobMapper.insert(any(EvaluationQuestionGenerationJob.class))).thenAnswer(invocation -> {
            EvaluationQuestionGenerationJob job = invocation.getArgument(0);
            job.setId(101L);
            return 1;
        });
        when(itemMapper.insertBatch(any())).thenReturn(2);
        when(promptLoader.load(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.endsWith("all-evaluation-rules.txt")) {
                return """
                        {{questionTypeRules}}
                        course={{courseName}}, topic={{topic}}, count={{count}}, difficulty={{difficulty}},
                        optionCount={{optionCount}}, language={{language}},
                        programmingLanguage={{programmingLanguage}}, sqlDialect={{sqlDialect}}
                        """;
            }
            return "question type rules";
        });
        when(questionAiService.generateQuestions(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            return Flux.defer(() -> {
                int callNumber = llmCallCount.incrementAndGet();
                if (callNumber == 1) {
                    try {
                        secondStartedBeforeFirstCompleted.set(secondCallStarted.await(500, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.<String>error(e);
                    }
                } else {
                    secondCallStarted.countDown();
                }
                return Flux.just(responseForPrompt(prompt));
            });
        });

        EvaluationQuestionServiceImpl service = new EvaluationQuestionServiceImpl(
                promptLoader,
                evaluationQuestionMapper,
                learningSpaceMapper,
                objectMapper,
                jobMapper,
                itemMapper,
                transactionTemplate,
                questionAiService
        );

        GenerateQuestionDTO dto = new GenerateQuestionDTO();
        dto.setLearningSpaceId(10L);
        dto.setCourseName("Data Structures");
        dto.setTopic("Sorting");
        dto.setQuestionTypeCountList(List.of(
                typeCount("single_choice", 1, 3),
                typeCount("judge", 1, 4)
        ));
        setCurrentUserId(1L);

        service.generateQuestions(dto, new SseEmitter(1_000L));

        verify(evaluationQuestionMapper, timeout(2_000).times(2)).insert(any(EvaluationQuestion.class));
        assertTrue(secondStartedBeforeFirstCompleted.get());
    }

    @Test
    void doesNotStartAsyncGenerationWhenInitialItemInsertFails() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        QuestionAiService questionAiService = mock(QuestionAiService.class);
        EvaluationQuestionMapper evaluationQuestionMapper = mock(EvaluationQuestionMapper.class);
        LearningSpaceMapper learningSpaceMapper = mock(LearningSpaceMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EvaluationQuestionGenerationJobMapper jobMapper = mock(EvaluationQuestionGenerationJobMapper.class);
        EvaluationQuestionGenerationItemMapper itemMapper = mock(EvaluationQuestionGenerationItemMapper.class);
        TransactionTemplate transactionTemplate = passthroughTransactionTemplate();

        when(jobMapper.insert(any(EvaluationQuestionGenerationJob.class))).thenReturn(1);
        when(itemMapper.insertBatch(any()))
                .thenThrow(new RuntimeException("insert item failed"));

        EvaluationQuestionServiceImpl service = new EvaluationQuestionServiceImpl(
                promptLoader,
                evaluationQuestionMapper,
                learningSpaceMapper,
                objectMapper,
                jobMapper,
                itemMapper,
                transactionTemplate,
                questionAiService
        );

        GenerateQuestionDTO dto = new GenerateQuestionDTO();
        dto.setLearningSpaceId(10L);
        dto.setCourseName("Data Structures");
        dto.setTopic("Sorting");
        dto.setQuestionTypeCountList(List.of(typeCount("single_choice", 1, 3)));
        setCurrentUserId(1L);

        assertThrows(RuntimeException.class, () -> service.generateQuestions(dto, new SseEmitter(1_000L)));
        verify(questionAiService, never()).generateQuestions(anyString());
        verify(evaluationQuestionMapper, never()).insert(any(EvaluationQuestion.class));
    }

    private void setCurrentUserId(Long userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(userId);
        ThreadLocalUtils.set(claims);
    }

    private String responseForPrompt(String prompt) {
        int count = parseQuestionCount(prompt);
        if (count <= 0) {
            count = 1;
        }

        StringBuilder questions = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                questions.append(",");
            }
            questions.append("""
                    {"questionType":"single_choice","difficulty":3,"stem":"q%s","optionsJson":[],"answerJson":{}}
                    """.formatted(i + 1));
        }
        return "{\"questions\":[" + questions + "]}";
    }

    private int parseQuestionCount(String prompt) {
        Matcher matcher = COUNT_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private GenerateQuestionDTO.QuestionTypeCountDTO typeCount(String questionType, int count, int difficulty) {
        GenerateQuestionDTO.QuestionTypeCountDTO dto = new GenerateQuestionDTO.QuestionTypeCountDTO();
        dto.setQuestionType(questionType);
        dto.setCount(count);
        dto.setDifficulty(difficulty);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private TransactionTemplate passthroughTransactionTemplate() {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactionTemplate;
    }
}
