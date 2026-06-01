package com.smartorder.edu.student.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.util.IdGenerator;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationJob;
import com.smartorder.edu.student.domain.enums.PromptFile;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationJobMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import com.smartorder.edu.student.util.PromptLoader;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.smartorder.edu.common.util.ThreadLocalUtils.getCurrentUserId;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationQuestionServiceImpl implements IEvaluationQuestionService {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "evaluation-question-generator-" + THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private final PromptLoader promptLoader;
    private final EvaluationQuestionMapper evaluationQuestionMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final ObjectMapper objectMapper;
    private final EvaluationQuestionGenerationJobMapper generationJobMapper;
    private final EvaluationQuestionGenerationItemMapper generationItemMapper;
    private final TransactionTemplate transactionTemplate;
    private final QuestionAiService questionAiService;

    private static final int DEFAULT_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 5;
    private static final int DEFAULT_OPTION_COUNT = 4;
    private static final int DEFAULT_MAX_RETRY_COUNT = 3;
    private static final String DEFAULT_PROGRAMMING_LANGUAGE = "Java";
    private static final String DEFAULT_SQL_DIALECT = "MySQL";
    private static final String PROMPTS_DIR = "prompts/";
    private static final String COMMON_RULES_FILE = "prompts/all-evaluation-rules.txt";

    @Override
    public void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter) {
        GenerationContext context = transactionTemplate.execute(
                status -> {
                    try {
                        return createGenerationContext(dto);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
        if (context == null) {
            throw new BusinessException("生成问题失败");
        }
        log.info("生成问题任务开始: jobId={}, jobNo={}, totalCount={}",
                context.job().getId(), context.job().getJobNo(), context.job().getTotalCount());
        sendEvent(emitter, "job-created", Map.of(
                "jobId", context.job().getId(),
                "jobNo", context.job().getJobNo(),
                "totalCount", context.job().getTotalCount(),
                "description", "问题开始生成"
        ));

        CompletableFuture.runAsync(() -> runGeneration(context, emitter), executor);
    }

    private GenerationContext createGenerationContext(GenerateQuestionDTO dto) throws Exception {
        if (dto.getQuestionTypeCountList() == null || dto.getQuestionTypeCountList().isEmpty()) {
            throw new BusinessException("问题类型数量列表不能为空");
        }

        String courseName = resolveCourseName(dto);
        String topic = trimTextOrDefault(dto.getTopic(), "basic knowledge");
        LocalDateTime now = LocalDateTime.now();
        int totalCount = dto.getQuestionTypeCountList().stream()
                .mapToInt(typeCount -> resolveCount(typeCount.getCount()))
                .sum();

        long id = IdGenerator.nextId();
        EvaluationQuestionGenerationJob job = EvaluationQuestionGenerationJob.builder()
                .id(id)
                .jobNo("QG-" + id)
                .userId(getCurrentUserId())
                .learningSpaceId(dto.getLearningSpaceId())
                .chapterId(dto.getChapterId())
                .knowledgePointId(dto.getKnowledgePointId())
                .courseName(courseName)
                .topic(topic)
                .requestPayload(toJson(dto))
                .status("pending")
                .totalCount(totalCount)
                .successCount(0)
                .failedCount(0)
                .progress(0)
                .currentStep("created")
                .createdAt(now)
                .updatedAt(now)
                .build();
        generationJobMapper.insert(job);

        List<EvaluationQuestionGenerationItem> items = createGenerationItems(job.getId(), dto, now);
        generationItemMapper.insertBatch(items);


        return new GenerationContext(dto, courseName, topic, job, items);
    }

    private List<EvaluationQuestionGenerationItem> createGenerationItems(Long jobId, GenerateQuestionDTO dto, LocalDateTime now) {
        List<EvaluationQuestionGenerationItem> items = new ArrayList<>();
        int sequenceNo = 1;

        for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : dto.getQuestionTypeCountList()) {
            int count = resolveCount(typeCount.getCount());
            int difficulty = resolveDifficulty(typeCount.getDifficulty());
            int optionCount = resolveOptionCount(typeCount.getOptionCount());

            for (int i = 0; i < count; i++) {
                EvaluationQuestionGenerationItem item = EvaluationQuestionGenerationItem.builder()
                        .id(IdGenerator.nextId())
                        .jobId(jobId)
                        .questionType(typeCount.getQuestionType())
                        .difficulty(difficulty)
                        .optionCount(optionCount)
                        .sequenceNo(sequenceNo++)
                        .status("pending")
                        .retryCount(0)
                        .maxRetryCount(DEFAULT_MAX_RETRY_COUNT)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                items.add(item);
            }
        }
        return items;
    }

    private void runGeneration(GenerationContext context, SseEmitter emitter) {
        AtomicInteger successCount = new AtomicInteger(0);
        Set<Long> successfulItemIds = ConcurrentHashMap.newKeySet();

        updateJobRunning(context.job().getId());

        try {
            List<GenerationBatch> batches = createGenerationBatches(context);
            validateGenerationBatches(batches);
            log.info("并行生成问题开始: jobId={}, batchCount={}, totalCount={}",
                    context.job().getId(), batches.size(), context.job().getTotalCount());
            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(
                            () -> generateBatch(context, batch, emitter, successCount, successfulItemIds),
                            executor
                    ))
                    .toList();
            waitForBatches(futures);

            updateJobFinished(context.job().getId(), "success", successCount.get(), 0, null);
            log.info("生成问题任务完成: jobId={}, successCount={}, failedCount=0",
                    context.job().getId(), successCount.get());
            completeEmitter(emitter);

        } catch (Exception e) {
            int failedCount = context.job().getTotalCount() - successfulItemIds.size();
            markUnfinishedItemsFailed(context.items(), successfulItemIds, e);
            updateJobFinished(context.job().getId(), resolveTerminalStatus(successfulItemIds.size(), failedCount),
                    successfulItemIds.size(), failedCount, e.getMessage());
            log.error("异步生成问题失败: jobId={}, successCount={}, failedCount={}",
                    context.job().getId(), successfulItemIds.size(), failedCount, e);
            completeEmitterWithError(emitter, e);
        }
    }

    private List<GenerationBatch> createGenerationBatches(GenerationContext context) {
        List<GenerationBatch> batches = new ArrayList<>();
        int itemCursor = 0;

        for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : context.dto().getQuestionTypeCountList()) {
            int count = resolveCount(typeCount.getCount());
            if (itemCursor + count > context.items().size()) {
                throw new BusinessException("Generation item count does not match request count");
            }

            List<EvaluationQuestionGenerationItem> batchItems =
                    List.copyOf(context.items().subList(itemCursor, itemCursor + count));
            itemCursor += count;

            batches.add(new GenerationBatch(
                    typeCount.getQuestionType(),
                    count,
                    resolveDifficulty(typeCount.getDifficulty()),
                    resolveOptionCount(typeCount.getOptionCount()),
                    batchItems
            ));
        }

        if (itemCursor != context.items().size()) {
            throw new BusinessException("Generation item count does not match request count");
        }
        return batches;
    }

    private void validateGenerationBatches(List<GenerationBatch> batches) {
        for (GenerationBatch batch : batches) {
            if (QuestionScore.fromCode(batch.questionType()) == QuestionScore.UNKNOWN) {
                throw new BusinessException("Unsupported question type: " + batch.questionType());
            }
        }
    }

    private void waitForBatches(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException("Question generation failed: " + cause.getMessage());
        }
    }

    private void generateBatch(GenerationContext context,
                               GenerationBatch batch,
                               SseEmitter emitter,
                               AtomicInteger successCount,
                               Set<Long> successfulItemIds) {

        markItemsRunning(batch.items());

        log.info("准备生成题型批次: jobId={}, questionType={}, difficulty={}, count={}",
                context.job().getId(), batch.questionType(), batch.difficulty(), batch.count());

        String userPrompt = buildUserPrompt(
                context.courseName(),
                context.topic(),
                batch.questionType(),
                batch.count(),
                batch.difficulty(),
                batch.optionCount()
        );
        log.debug("生成问题提示词构建完成: jobId={}, questionType={}, length={}",
                context.job().getId(), batch.questionType(), userPrompt.length());

        updateJobStep(context.job().getId(), "generating " + batch.questionType());
        String llmJson = callStreamingModelAndWait(userPrompt, emitter, batch.questionType(), context.job().getId());
        JsonNode questionsNode = parseLlmResponse(llmJson);

        if (questionsNode.size() != batch.count()) {
            throw new BusinessException("LLM returned " + questionsNode.size()
                    + " questions, expected " + batch.count());
        }

        for (int i = 0; i < questionsNode.size(); i++) {
            EvaluationQuestionGenerationItem item = batch.items().get(i);
            Long questionId = IdGenerator.nextId();
            EvaluationQuestion question = mapJsonToQuestion(
                    questionId,
                    questionsNode.get(i),
                    context.dto(),
                    context.courseName(),
                    batch.questionType(),
                    batch.difficulty(),
                    item.getSequenceNo()
            );

            evaluationQuestionMapper.insert(question);
            markItemSuccess(item.getId(), questionId, llmJson);
            successfulItemIds.add(item.getId());
            int generatedCount = successCount.incrementAndGet();
            updateJobProgress(context.job().getId(), generatedCount, 0, context.job().getTotalCount(),
                    "generated " + generatedCount + "/" + context.job().getTotalCount());
        }
        log.info("题型批次生成完成: jobId={}, questionType={}, count={}",
                context.job().getId(), batch.questionType(), batch.count());
    }

    private String buildUserPrompt(String courseName,
                                   String topic,
                                   String questionType,
                                   int count,
                                   int difficulty,
                                   int optionCount) {

        PromptFile promptFile = PromptFile.fromCode(questionType);
        String commonPrompt = promptLoader.load(COMMON_RULES_FILE);
        String typeRulePrompt = promptLoader.load(PROMPTS_DIR + promptFile.getPromptFileName());
        String mergedPrompt = commonPrompt.replace("{{questionTypeRules}}", typeRulePrompt);

        return PromptTemplate.from(mergedPrompt)
                .apply(Map.of(
                        "courseName", courseName,
                        "topic", topic,
                        "difficulty", difficulty,
                        "count", count,
                        "language", "zh-CN",
                        "optionCount", optionCount,
                        "programmingLanguage", DEFAULT_PROGRAMMING_LANGUAGE,
                        "sqlDialect", DEFAULT_SQL_DIALECT
                ))
                .text();
    }

    private String callStreamingModelAndWait(String userPrompt, SseEmitter emitter, String questionType, Long jobId) {
        StringBuilder fullContent = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            questionAiService.generateQuestions(userPrompt)
                    .subscribe(
                            partialResponse -> {
                                log.debug("LLM streaming chunk: {}", partialResponse);
                                fullContent.append(partialResponse);
                                sendEvent(emitter, "quiz-token",
                                        Map.of(
                                                "questionType", questionType,
                                                "jobId", jobId,
                                                "chunk", partialResponse
                                        ));
                            },
                            error -> {
                                log.error("LLM streaming generation failed", error);
                                errorRef.set(error);
                                latch.countDown();
                            },
                            () -> {
                                log.info("LLM streaming generation completed");
                                latch.countDown();
                            }
                    );
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Interrupted while waiting for LLM response");
        } catch (Exception e) {
            throw new BusinessException("LLM generation failed: " + e.getMessage());
        }

        if (errorRef.get() != null) {
            throw new BusinessException("LLM generation failed: " + errorRef.get().getMessage());
        }

        String result = fullContent.toString().strip();
        if (result.isBlank()) {
            throw new BusinessException("LLM response is empty");
        }

        return result;
    }

    private EvaluationQuestion mapJsonToQuestion(Long questionId,
                                                 JsonNode q,
                                                 GenerateQuestionDTO dto,
                                                 String courseName,
                                                 String questionType,
                                                 int difficulty,
                                                 int sequence) {

        LocalDateTime now = LocalDateTime.now();
        EvaluationQuestion question = new EvaluationQuestion();

        question.setId(questionId);
        question.setQuestionCode(buildQuestionCode(courseName, questionType, sequence, questionId));
        question.setLearningSpaceId(dto.getLearningSpaceId());
        question.setChapterId(dto.getChapterId());
        question.setKnowledgePointId(dto.getKnowledgePointId());
        question.setQuestionType(readText(q, "questionType", questionType));
        question.setDifficulty(readInt(q, "difficulty", difficulty));
        question.setScore(BigDecimal.valueOf(1));
        question.setStem(readRequiredText(q, "stem"));
        question.setOptionsJson(readJsonArrayAsList(q, "optionsJson"));
        question.setAnswerJson(readJsonObjectAsMap(q, "answerJson"));
        question.setExplanation(readText(q, "explanation", ""));
        question.setAbilityDimension(readText(q, "abilityDimension", null));
        question.setCommonMistakesJson(readJsonArrayAsList(q, "commonMistakesJson"));
        question.setTagsJson(readJsonArrayAsStringList(q, "tagsJson"));
        question.setGenerationSource("agent_generated");
        question.setStatus(1);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);

        return question;
    }

    private void updateJobRunning(Long jobId) {
        buildAndUpdate(jobId, update -> update.setCurrentStep("running"));
    }

    private void updateJobStep(Long jobId, String currentStep) {
        buildAndUpdate(jobId, update -> update.setCurrentStep(currentStep));
    }

    private void updateJobProgress(Long jobId, int successCount, int failedCount, int totalCount, String currentStep) {
        buildAndUpdate(jobId, update -> {
            update.setSuccessCount(successCount);
            update.setFailedCount(failedCount);
            update.setProgress(calculateProgress(successCount, failedCount, totalCount));
            update.setCurrentStep(currentStep);
        });
    }

    private void updateJobFinished(Long jobId, String status, int successCount, int failedCount, String errorMessage) {
        buildAndUpdate(jobId, update -> {
            update.setStatus(status);
            update.setSuccessCount(successCount);
            update.setFailedCount(failedCount);
            update.setProgress(calculateProgress(successCount, failedCount, successCount + failedCount));
            update.setCurrentStep("finished");
            update.setErrorMessage(errorMessage);
            update.setFinishedAt(LocalDateTime.now());
        });
    }

    private void buildAndUpdate(Long jobId, Consumer<EvaluationQuestionGenerationJob> customizer) {
        EvaluationQuestionGenerationJob update = new EvaluationQuestionGenerationJob();
        update.setId(jobId);
        update.setUpdatedAt(LocalDateTime.now());
        customizer.accept(update);
        generationJobMapper.updateById(update);
    }

    private void markItemsRunning(List<EvaluationQuestionGenerationItem> items) {
        UpdateWrapper<EvaluationQuestionGenerationItem> wrapper = new UpdateWrapper<EvaluationQuestionGenerationItem>()
                .in("id", items.stream().map(EvaluationQuestionGenerationItem::getId).toList())
                .set("status", "running")
                .set("started_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now());
        generationItemMapper.update(null, wrapper);
    }

    private void markItemSuccess(Long itemId, Long questionId, String rawResponse) {
        EvaluationQuestionGenerationItem update = new EvaluationQuestionGenerationItem();
        update.setId(itemId);
        update.setQuestionId(questionId);
        update.setStatus("success");
        update.setRawResponse(rawResponse);
        update.setFinishedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        generationItemMapper.updateById(update);
    }

    private void markUnfinishedItemsFailed(List<EvaluationQuestionGenerationItem> items, Set<Long> successfulItemIds, Exception e) {
        UpdateWrapper<EvaluationQuestionGenerationItem> wrapper = new UpdateWrapper<EvaluationQuestionGenerationItem>()
                .in("id", items.stream()
                        .filter(item -> !successfulItemIds.contains(item.getId()))
                        .map(EvaluationQuestionGenerationItem::getId)
                        .toList())
                .set("status", "failed")
                .set("error_message", e.getMessage())
                .set("finished_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now());
        generationItemMapper.update(null, wrapper);
    }

    private String resolveCourseName(GenerateQuestionDTO dto) {
        if (dto.getCourseName() != null && !dto.getCourseName().isBlank()) {
            return dto.getCourseName().strip();
        }

        LearningSpace learningSpace = learningSpaceMapper.selectById(dto.getLearningSpaceId());
        if (learningSpace == null || learningSpace.getName() == null || learningSpace.getName().isBlank()) {
            return "unknown course";
        }
        return learningSpace.getName();
    }

    private String toJson(GenerateQuestionDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize generation request");
        }
    }

    private int resolveCount(Integer count) {
        return count == null ? DEFAULT_COUNT : count;
    }

    private int resolveDifficulty(Integer difficulty) {
        return difficulty == null ? DEFAULT_DIFFICULTY : difficulty;
    }

    private int resolveOptionCount(Integer optionCount) {
        return optionCount == null ? DEFAULT_OPTION_COUNT : optionCount;
    }

    private int calculateProgress(int successCount, int failedCount, int totalCount) {
        if (totalCount <= 0) {
            return 100;
        }
        return Math.min(100, (successCount + failedCount) * 100 / totalCount);
    }

    private String resolveTerminalStatus(int successCount, int failedCount) {
        if (failedCount == 0) {
            return "success";
        }
        return successCount > 0 ? "partial_success" : "failed";
    }

    private String trimTextOrDefault(String topic, String defaultTopic) {
        if (topic == null || topic.isBlank()) {
            return defaultTopic;
        }
        return topic.strip();
    }

    private String buildQuestionCode(String courseName, String questionType, int sequence, Long questionId) {
        String normalizedCourse = courseName == null ? "course" : courseName.strip().replaceAll("\\s+", "-");
        return normalizedCourse + "-" + questionType + "-" + sequence + "-" + questionId;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(JSON.toJSONString(data)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送已跳过: event={}, reason={}", name, e.getMessage());
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 完成已跳过: {}", e.getMessage());
        }
    }

    private void completeEmitterWithError(SseEmitter emitter, Exception error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.debug("SSE 错误完成已跳过: {}", e.getMessage());
        }
    }

    private JsonNode parseLlmResponse(String response) {
        if (response == null) {
            throw new BusinessException("LLM response is empty");
        }

        String json = response.strip();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");

            if (firstNewline > 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).strip();
            }
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode questions = root.get("questions");
            if (questions == null || !questions.isArray()) {
                throw new BusinessException("LLM response JSON lacks questions array");
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", response, e);
            throw new BusinessException("Failed to parse LLM response as valid JSON");
        }
    }

    private String readText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }

        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private int readInt(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }

        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }

        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonArrayAsList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON array");
        }

        return objectMapper.convertValue(value, List.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> readJsonArrayAsStringList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON array");
        }

        return objectMapper.convertValue(value, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObjectAsMap(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return Map.of();
        }

        if (!value.isObject()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON object");
        }

        return objectMapper.convertValue(value, Map.class);
    }

    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            throw new BusinessException("LLM question lacks required field: " + fieldName);
        }

        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("LLM question field is empty: " + fieldName);
        }

        return text;
    }

    private record GenerationContext(
            GenerateQuestionDTO dto,
            String courseName,
            String topic,
            EvaluationQuestionGenerationJob job,
            List<EvaluationQuestionGenerationItem> items
    ) {
    }

    private record GenerationBatch(
            String questionType,
            int count,
            int difficulty,
            int optionCount,
            List<EvaluationQuestionGenerationItem> items
    ) {
    }
}
