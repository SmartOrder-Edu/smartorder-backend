package com.smartorder.edu.learning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.service.impl.ResourceContentGenerator;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceStreamingGenerationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int CHUNK_SIZE = 220;

    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ResourceGenerationContextBuilder contextBuilder;
    private final ResourceContentGenerator resourceContentGenerator;
    private final IResourceContentService resourceContentService;
    private final IResourceService resourceService;

    public ResourceContent generate(Long userId, Resource resource, Consumer<String> chunkConsumer) {
        if (resource == null || resource.getId() == null) {
            throw new BusinessException("资源不存在，无法生成");
        }
        ResourceGenerationContext context = contextBuilder.build(userId, resource);
        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (streamingChatModel == null || !supportsLlmStreaming(resource.getResourceType())) {
            return generateWithFallback(userId, resource, chunkConsumer);
        }

        String prompt = buildPrompt(resource, context);
        StringBuilder contentBuilder = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();

        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                contentBuilder.append(partialResponse);
                chunkConsumer.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        awaitCompletion(resource, latch);
        if (errorRef.get() != null) {
            log.warn("[ResourceStreamingGeneration] streaming failed resource={} type={}",
                    resource.getId(), resource.getResourceType(), errorRef.get());
            throw new BusinessException("学习资源流式生成失败：" + errorRef.get().getMessage());
        }

        String content = sanitize(contentBuilder.toString(), resource.getResourceType());
        if (content.isBlank()) {
            throw new BusinessException("学习资源生成结果为空");
        }
        ResourceContent saved = resourceContentService.createContent(
                resource.getId(),
                contentType(resource.getResourceType()),
                content,
                reviewResult("passed", "streaming_llm", resource.getResourceType())
        );
        resourceService.markGenerated(resource.getId(), resourceContentGenerator.fileFormat(resource.getResourceType()));
        return saved;
    }

    private ResourceContent generateWithFallback(Long userId, Resource resource, Consumer<String> chunkConsumer) {
        ResourceContent generated = resourceContentGenerator.generate(resource, userId);
        emitBuffered(generated.getContent(), chunkConsumer);
        ResourceContent saved = resourceContentService.createContent(
                resource.getId(),
                generated.getContentType(),
                generated.getContent(),
                generated.getReviewResult()
        );
        resourceService.markGenerated(resource.getId(), resourceContentGenerator.fileFormat(resource.getResourceType()));
        return saved;
    }

    private void awaitCompletion(Resource resource, CountDownLatch latch) {
        try {
            boolean completed = latch.await(180, TimeUnit.SECONDS);
            if (!completed) {
                throw new BusinessException("学习资源生成超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("学习资源生成被中断");
        }
    }

    private boolean supportsLlmStreaming(String resourceType) {
        return switch (resourceType) {
            case "doc", "html", "mind_map", "quiz" -> true;
            default -> false;
        };
    }

    private String buildPrompt(Resource resource, ResourceGenerationContext context) {
        return switch (resource.getResourceType()) {
            case "html" -> """
                    你是严谨的学习资料生成 Agent。请根据上下文生成一份可直接嵌入学习空间详情页的 HTML 学习资源。

                    约束：
                    - 只输出 HTML 片段，不要输出 Markdown、代码围栏、完整 html/head/body、JSON 或额外解释。
                    - 推荐根节点使用 <article class="generated-resource-html">。
                    - 可以使用内联 style 或 <style>，但样式选择器应限定在 .generated-resource-html 内。
                    - 不要输出 script、iframe、object、embed、form、link、meta 标签，不要写 onClick/onload 等事件属性。
                    - 内容必须围绕知识点，要包含目标、核心概念、步骤、例题、易错点和复盘清单。

                    %s
                    """.formatted(contextBlock(context));
            case "mind_map" -> """
                    你是严谨的学习资料生成 Agent。请根据上下文生成 Markdown 思维导图资料。

                    约束：
                    - 只输出 Markdown，不要输出额外解释。
                    - 必须包含一个 Mermaid mindmap 代码块。
                    - 必须包含「核心层级」「易错关系」「复盘问题」三个部分。
                    - Mermaid 节点文字使用中文短语，避免括号和特殊符号。

                    %s
                    """.formatted(contextBlock(context));
            case "quiz" -> """
                    你是严谨的学习测评 Agent。请根据上下文生成一组 JSON 练习题。

                    约束：
                    - 只输出 JSON 对象，不要输出代码围栏或额外解释。
                    - JSON 顶层字段：title、knowledgePoint、questions。
                    - questions 至少 3 题，包含单选题和简答题。
                    - 每题包含 type、stem、answer 或 referenceAnswer、explanation、difficulty。

                    %s
                    """.formatted(contextBlock(context));
            default -> """
                    你是严谨的学习资料生成 Agent。请根据上下文生成一份可直接学习的 Markdown 讲义。

                    约束：
                    - 只输出 Markdown，不要输出 JSON、代码围栏或额外解释。
                    - 内容必须围绕知识点，不要泛泛而谈。
                    - 必须包含这些二级标题：学习目标、核心概念、例题讲解、易错点、复盘问题。
                    - 例题讲解至少包含 1 个具体例子，不能只写抽象步骤。
                    - 语言要适合学生自学，避免空洞口号。

                    %s
                    """.formatted(contextBlock(context));
        };
    }

    private String contextBlock(ResourceGenerationContext context) {
        return """
                学习空间：%s
                当前目标：%s
                当前任务：%s
                路径节点：%s
                知识点：%s
                使用场景：%s
                用户意图：%s
                目标学习时长：%d 分钟
                生成原因：%s

                请开始生成。
                """.formatted(
                value(context.space() == null ? null : context.space().getName()),
                value(context.space() == null ? null : context.space().getGoals()),
                value(context.task() == null ? null : context.task().getTitle()),
                value(context.pathNode() == null ? null : context.pathNode().getTitle()),
                value(context.knowledgePoint()),
                value(context.studyScenario()),
                value(context.userIntent()),
                context.targetMinutes(),
                value(context.reason())
        );
    }

    private void emitBuffered(String content, Consumer<String> chunkConsumer) {
        String value = content == null ? "" : content;
        for (int start = 0; start < value.length(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, value.length());
            chunkConsumer.accept(value.substring(start, end));
        }
    }

    private String sanitize(String content, String resourceType) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int lastFence = value.lastIndexOf("```");
            if (lastFence >= 0) {
                value = value.substring(0, lastFence).trim();
            }
        }
        if ("html".equals(resourceType)) {
            return value.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                    .replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "")
                    .replaceAll("(?is)\\son[a-z]+\\s*=\\s*(['\"]).*?\\1", "")
                    .replaceAll("(?i)javascript:", "");
        }
        return value;
    }

    private String contentType(String resourceType) {
        return switch (resourceType) {
            case "html" -> "html";
            case "quiz" -> "json";
            default -> "markdown";
        };
    }

    private String reviewResult(String status, String source, String resourceType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("source", source);
        result.put("resourceType", resourceType);
        result.put("summary", "资源内容通过流式生成并已完成基础落库。");
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"" + status + "\",\"source\":\"" + source + "\"}";
        }
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }
}
