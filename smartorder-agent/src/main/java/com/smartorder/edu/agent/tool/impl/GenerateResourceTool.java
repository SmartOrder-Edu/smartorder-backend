package com.smartorder.edu.agent.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.dto.ResourceGenerationRequest;
import com.smartorder.edu.learning.service.ResourceGenerationResult;
import com.smartorder.edu.learning.service.ResourceGenerationService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成资源工具——调用 LLM 生成文本学习资源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateResourceTool implements ToolHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatLanguageModel;
    private final AgentResourceLogMapper resourceLogMapper;
    private final PromptLoader promptLoader;
    private final ResourceGenerationService resourceGenerationService;

    @Override
    public String name() {
        return "generateResource";
    }

    @Override
    public String description() {
        return "为指定知识点生成学习资源，如学习笔记、知识点总结、思维导图大纲、案例分析等。生成的资源为文本格式，会自动记录到资源库中供学生查看";
    }

    @Override
    public Class<?> requestType() {
        return GenerateResourceRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.RESOURCE;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GenerateResourceRequest req = (GenerateResourceRequest) request;
        Long userId = context.getUserId();

        log.info("[GenerateResource] userId={}, spaceId={}, topic={}, type={}", userId, req.getSpaceId(), req.getTopic(), req.getResourceType());

        AgentResourceLog resourceLog = baseResourceLog(req, userId);
        try {
            String prompt = buildResourcePrompt(req.getTopic(), req.getResourceType());
            String content = chatLanguageModel.chat(prompt);

            ResourceGenerationRequest generationRequest = new ResourceGenerationRequest();
            generationRequest.setSpaceId(req.getSpaceId());
            generationRequest.setTopic(req.getTopic());
            generationRequest.setResourceType(req.getResourceType());
            generationRequest.setPhaseIndex(req.getPhaseIndex());
            generationRequest.setUserIntent(context.getCurrentUserMessage());
            generationRequest.setPackageMode("agent_tool");
            generationRequest.setGeneratedContent(content);
            generationRequest.setGeneratedContentType("markdown");

            ResourceGenerationResult generated = resourceGenerationService.generateNew(userId, generationRequest);

            resourceLog.setStatus("COMPLETED");
            resourceLog.setResultUrl("/resources/" + generated.resource().getId());
            resourceLog.setGenerationParams(toJson(generationParams(req, context, generated.resource().getId())));
            resourceLogMapper.insert(resourceLog);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resourceId", generated.resource().getId());
            result.put("resourceLogId", resourceLog.getId());
            result.put("title", generated.resource().getTitle());
            result.put("resourceType", generated.resource().getResourceType());
            result.put("url", resourceLog.getResultUrl());
            result.put("contentPreview", preview(content));
            result.put("message", "资源已生成并写入学习空间资源库");
            return result;
        } catch (Exception e) {
            resourceLog.setStatus("FAILED");
            resourceLog.setErrorMessage(e.getMessage());
            resourceLog.setGenerationParams(toJson(generationParams(req, context, null)));
            resourceLogMapper.insert(resourceLog);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e);
        }
    }

    private String buildResourcePrompt(String topic, String resourceType) {
        String typeName = resourceType != null ? resourceType : "学习资料";
        return PromptLoader.format(promptLoader.getResourceGenTemplate(), typeName, topic);
    }

    private AgentResourceLog baseResourceLog(GenerateResourceRequest req, Long userId) {
        AgentResourceLog resourceLog = new AgentResourceLog();
        resourceLog.setSpaceId(req.getSpaceId());
        resourceLog.setPhaseIndex(req.getPhaseIndex() != null ? req.getPhaseIndex() : 1);
        resourceLog.setResourceType(req.getResourceType() != null ? req.getResourceType() : "doc");
        resourceLog.setTitle((req.getTopic() != null ? req.getTopic() : "当前知识点")
                + " - " + (req.getResourceType() != null ? req.getResourceType() : "学习资料"));
        resourceLog.setUserId(userId);
        resourceLog.setRetryCount(0);
        return resourceLog;
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 1200 ? content : content.substring(0, 1200) + "...";
    }

    private Map<String, Object> generationParams(
            GenerateResourceRequest req,
            ToolInvokeContext context,
            Long resourceId
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topic", req.getTopic());
        params.put("type", req.getResourceType());
        params.put("resourceId", resourceId);
        params.put("traceId", context.getTraceId());
        return params;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @Data
    public static class GenerateResourceRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间ID")
        private Long spaceId;
        @dev.langchain4j.model.output.structured.Description("知识点主题，必填，例如'进程调度算法'")
        private String topic;
        @dev.langchain4j.model.output.structured.Description("资源类型，如 DOC、MIND_MAP、CASE_STUDY、QUIZ")
        private String resourceType;
        @dev.langchain4j.model.output.structured.Description("学习阶段序号，从1开始")
        private Integer phaseIndex;
    }
}
