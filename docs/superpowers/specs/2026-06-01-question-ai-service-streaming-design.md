# Question AI Service Streaming Design

## Goal

Change evaluation question generation so `EvaluationQuestionServiceImpl` uses the `QuestionAiService` AI service interface instead of calling `openAiStreamingChatModel.chat(...)` directly.

## Current Behavior

`EvaluationQuestionServiceImpl.callStreamingModelAndWait` builds a LangChain4j `ChatRequest`, attaches a `StreamingChatResponseHandler`, collects streamed tokens into a full JSON response, forwards each token through SSE, then parses and persists generated questions.

The service already injects `QuestionAiService`, and `QuestionAiService.generateQuestions(@UserMessage String prompt)` returns `Flux<String>`. However, `callStreamingModelAndWait` still depends on `OpenAiStreamingChatModel`, and there is an incomplete `questionAiService.generateQuestions()` call that should be removed or completed.

## Proposed Behavior

Keep the existing generation workflow:

1. Create the durable generation job and item rows.
2. Build the prompt for each question type batch.
3. Generate batches concurrently.
4. Stream partial LLM output to the frontend as `quiz-token` SSE events.
5. Wait for the full response, parse JSON, insert questions, and update job/item status.

Only replace the low-level LLM call inside `callStreamingModelAndWait`:

```java
questionAiService.generateQuestions(userPrompt)
        .subscribe(
                partialResponse -> {
                    fullContent.append(partialResponse);
                    sendEvent(emitter, "quiz-token", Map.of(
                            "questionType", questionType,
                            "jobId", jobId,
                            "chunk", partialResponse
                    ));
                },
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                },
                latch::countDown
        );
```

The method will still use `CountDownLatch` because the downstream code needs the complete JSON payload before it can validate, parse, and persist questions.

## Files To Change

- `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImpl.java`
  - Remove the `OpenAiStreamingChatModel` field and constructor dependency.
  - Remove unused LangChain4j chat request and handler imports.
  - Rewrite `callStreamingModelAndWait` to subscribe to `questionAiService.generateQuestions(userPrompt)`.
  - Preserve existing SSE event names and payload shape.

- `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/EvaluationQuestionServiceImplTest.java`
  - Replace `OpenAiStreamingChatModel` mocks with `QuestionAiService` mocks.
  - Return `Flux.just(response)` for successful streaming cases.
  - Verify initial insert failures do not invoke `questionAiService.generateQuestions(...)`.

## Error Handling

If `QuestionAiService.generateQuestions` emits an error, store it in `errorRef`, release the latch, and throw `BusinessException("LLM generation failed: ...")` after waiting. This preserves the existing job failure path, including failed item updates and SSE error completion.

If the completed stream is blank, continue throwing `BusinessException("LLM response is empty")`.

## Testing

Run the student module unit test that covers question generation:

```bash
./mvnw -pl smartorder-student -Dtest=EvaluationQuestionServiceImplTest test
```

The important assertions are:

- Job and generation items are created before async generation.
- Different question types still start generation in parallel.
- Initial item insert failure does not call the AI service.
- Generated questions are still parsed and persisted from the collected streamed response.
