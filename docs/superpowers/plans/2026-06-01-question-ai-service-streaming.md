# Question AI Service Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route evaluation question generation through `QuestionAiService.generateQuestions(...)` instead of directly calling `openAiStreamingChatModel.chat(...)`.

**Architecture:** Keep the existing asynchronous question generation pipeline, durable job/item updates, SSE events, JSON parsing, and persistence flow. Only replace the low-level streaming model call with the existing LangChain4j AI service interface that returns `Flux<String>`.

**Tech Stack:** Java 17, Spring Boot, LangChain4j AI Services, Reactor `Flux`, JUnit 5, Mockito, Maven Wrapper.

---

## File Structure

- Modify `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java`
  - Owns generation orchestration and the blocking bridge from streamed AI output to complete JSON.
  - Remove direct `OpenAiStreamingChatModel` dependency.
  - Subscribe to `QuestionAiService.generateQuestions(userPrompt)` inside `callStreamingModelAndWait`.

- Modify `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java`
  - Owns unit coverage for job creation, batch concurrency, and failure before async generation.
  - Replace direct streaming model mocks with `QuestionAiService` mocks returning `Flux<String>`.

## Task 1: Convert Tests To The AI Service Interface

**Files:**
- Modify: `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java`

- [ ] **Step 1: Replace direct model imports with AI service imports**

Update the imports near the top of `EvaluationQuestionServiceImplTest.java`.

Remove these imports:

```java
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
```

Add these imports:

```java
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import reactor.core.publisher.Flux;
```

Add this static import:

```java
import static org.mockito.ArgumentMatchers.anyString;
```

- [ ] **Step 2: Rewrite the first test setup**

In `createsGenerationJobAndExpandedItemsBeforeAsyncGenerationRuns`, replace:

```java
OpenAiStreamingChatModel streamingChatModel = mock(OpenAiStreamingChatModel.class);
```

with:

```java
QuestionAiService questionAiService = mock(QuestionAiService.class);
```

Replace the `doAnswer(...).when(streamingChatModel).chat(...)` block with:

```java
when(questionAiService.generateQuestions(anyString())).thenAnswer(invocation ->
        Flux.just(responseForPrompt(invocation.getArgument(0)))
);
```

Replace the service construction with:

```java
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
```

- [ ] **Step 3: Rewrite the parallel batch test setup**

In `startsGenerationForDifferentQuestionTypesInParallel`, replace:

```java
OpenAiStreamingChatModel streamingChatModel = mock(OpenAiStreamingChatModel.class);
```

with:

```java
QuestionAiService questionAiService = mock(QuestionAiService.class);
```

Replace the `doAnswer(...).when(streamingChatModel).chat(...)` block with:

```java
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
```

Replace the service construction with:

```java
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
```

- [ ] **Step 4: Rewrite the initial insert failure test setup**

In `doesNotStartAsyncGenerationWhenInitialItemInsertFails`, replace:

```java
OpenAiStreamingChatModel streamingChatModel = mock(OpenAiStreamingChatModel.class);
```

with:

```java
QuestionAiService questionAiService = mock(QuestionAiService.class);
```

Replace the service construction with:

```java
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
```

Replace the model verification:

```java
verify(streamingChatModel, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
```

with:

```java
verify(questionAiService, never()).generateQuestions(anyString());
```

- [ ] **Step 5: Replace ChatRequest-based helper methods**

Replace:

```java
private String responseForRequest(ChatRequest request) {
    int count = requestedQuestionCount(request);
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

private int requestedQuestionCount(ChatRequest request) {
    return request.messages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(UserMessage::hasSingleText)
            .map(UserMessage::singleText)
            .map(this::parseQuestionCount)
            .filter(count -> count > 0)
            .findFirst()
            .orElse(1);
}
```

with:

```java
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
```

- [ ] **Step 6: Run the focused test and verify it fails for production code**

Run from `smartorder-backend`:

```powershell
.\mvnw.cmd -pl smartorder-student -Dtest=EvaluationQuestionServiceImplTest test
```

Expected: FAIL during compilation because `EvaluationQuestionServiceImpl` still has the `OpenAiStreamingChatModel` constructor dependency and the incomplete `questionAiService.generateQuestions()` call.

## Task 2: Route Production Streaming Through QuestionAiService

**Files:**
- Modify: `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java`
- Test: `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java`

- [ ] **Step 1: Remove direct streaming model imports**

In `EvaluationQuestionServiceImpl.java`, remove these imports:

```java
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
```

- [ ] **Step 2: Remove the direct model field**

Remove this field:

```java
private final OpenAiStreamingChatModel openAiStreamingChatModel;
```

Keep this existing field:

```java
private final QuestionAiService questionAiService;
```

- [ ] **Step 3: Replace `callStreamingModelAndWait`**

Replace the whole `callStreamingModelAndWait` method with:

```java
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
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run from `smartorder-backend`:

```powershell
.\mvnw.cmd -pl smartorder-student -Dtest=EvaluationQuestionServiceImplTest test
```

Expected: PASS. The job/item creation test inserts three generated questions, the parallel test confirms the second AI service subscription starts before the first finishes, and the initial insert failure test verifies `questionAiService.generateQuestions(...)` is not called.

## Task 3: Cleanup, Verification, And Commit

**Files:**
- Modify: `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java`
- Modify: `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java`

- [ ] **Step 1: Confirm direct model usage is gone from the target files**

Run from `smartorder-backend`:

```powershell
rg -n "OpenAiStreamingChatModel|openAiStreamingChatModel|StreamingChatResponseHandler|ChatRequest" smartorder-student\src\main\java\com\smartorder\edu\student\service\impl\EvaluationQuestionServiceImpl.java smartorder-student\src\test\java\com\smartorder\edu\student\service\impl\EvaluationQuestionServiceImplTest.java
```

Expected: no matching lines. `rg` may exit with code 1 when there are no matches; that is acceptable for this check.

- [ ] **Step 2: Run the focused test one more time**

Run from `smartorder-backend`:

```powershell
.\mvnw.cmd -pl smartorder-student -Dtest=EvaluationQuestionServiceImplTest test
```

Expected: PASS with all tests in `EvaluationQuestionServiceImplTest` green.

- [ ] **Step 3: Review only the intended diff**

Run from `smartorder-backend`:

```powershell
git diff -- smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java
```

Expected: diff only removes direct model wiring, adds `QuestionAiService`-based test stubs, and rewrites `callStreamingModelAndWait`.

- [ ] **Step 4: Commit the implementation**

Run from `smartorder-backend` and stage only the two implementation files:

```powershell
git add smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java
git commit -m "refactor: route question generation through ai service"
```

Expected: one commit containing only the service and test changes. Existing unrelated working tree changes must remain unstaged.

## Self-Review Notes

- Spec coverage: The plan removes `OpenAiStreamingChatModel`, uses `QuestionAiService.generateQuestions(userPrompt)`, preserves `quiz-token` SSE events, keeps `CountDownLatch`, and updates the existing unit tests.
- Completion-word scan: No unresolved planning markers are present.
- Type consistency: The plan uses `QuestionAiService.generateQuestions(String)` returning `Flux<String>` in both production and tests.
