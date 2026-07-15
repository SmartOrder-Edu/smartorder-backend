package com.smartorder.edu.agent.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.learning.service.ppt.PptGenerationRequest;
import com.smartorder.edu.learning.service.ppt.PptGenerationResult;
import com.smartorder.edu.learning.service.ppt.PptGenerationService;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratePptResourceTool implements ToolHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PptGenerationService pptGenerationService;
    private final AgentResourceLogMapper resourceLogMapper;

    @Override
    public String name() {
        return "generatePptResource";
    }

    @Override
    public String description() {
        return "将学习空间中的现有资源转换为 PPT 课件。会调用 Docmee 模板筛选、随机模板和 PPT 生成接口，并写入资源库。";
    }

    @Override
    public Class<?> requestType() {
        return GeneratePptResourceRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.RESOURCE;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GeneratePptResourceRequest req = (GeneratePptResourceRequest) request;
        if (req.getSourceResourceId() == null) {
            throw new IllegalArgumentException("sourceResourceId can not be null");
        }
        Long userId = context.getUserId();
        log.info("[GeneratePptResource] userId={}, sourceResourceId={}, spaceId={}, topic={}, templateStyle={}",
                userId, req.getSourceResourceId(), req.getSpaceId(), req.getTopic(), req.getTemplateStyle());

        AgentResourceLog resourceLog = baseResourceLog(req, userId);
        try {
            PptGenerationRequest generationRequest = new PptGenerationRequest();
            generationRequest.setSpaceId(req.getSpaceId());
            generationRequest.setTopic(req.getTopic());
            generationRequest.setTemplateStyle(req.getTemplateStyle());
            generationRequest.setTargetSlides(req.getTargetSlides());
            generationRequest.setUserIntent(context.getCurrentUserMessage());

            PptGenerationResult generated = pptGenerationService.generateFromResource(
                    userId,
                    req.getSourceResourceId(),
                    generationRequest
            );

            resourceLog.setStatus("COMPLETED");
            resourceLog.setResultUrl("/resources/" + generated.resourceId());
            resourceLog.setGenerationParams(toJson(generationParams(req, context, generated)));
            resourceLogMapper.insert(resourceLog);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resourceId", generated.resourceId());
            result.put("resourceLogId", resourceLog.getId());
            result.put("pptId", generated.pptId());
            result.put("templateId", generated.templateId());
            result.put("resourceType", "ppt");
            result.put("fileUrl", generated.fileUrl());
            result.put("url", resourceLog.getResultUrl());
            result.put("message", "PPT 已生成并写入学习空间资源库");
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

    private AgentResourceLog baseResourceLog(GeneratePptResourceRequest req, Long userId) {
        AgentResourceLog resourceLog = new AgentResourceLog();
        resourceLog.setSpaceId(req.getSpaceId());
        resourceLog.setPhaseIndex(req.getPhaseIndex() != null ? req.getPhaseIndex() : 1);
        resourceLog.setResourceType("ppt");
        resourceLog.setTitle((req.getTopic() != null ? req.getTopic() : "当前资源") + " - PPT");
        resourceLog.setUserId(userId);
        resourceLog.setRetryCount(0);
        return resourceLog;
    }

    private Map<String, Object> generationParams(
            GeneratePptResourceRequest req,
            ToolInvokeContext context,
            PptGenerationResult generated
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sourceResourceId", req.getSourceResourceId());
        params.put("topic", req.getTopic());
        params.put("templateStyle", req.getTemplateStyle());
        params.put("targetSlides", req.getTargetSlides());
        params.put("resourceId", generated == null ? null : generated.resourceId());
        params.put("pptId", generated == null ? null : generated.pptId());
        params.put("templateId", generated == null ? null : generated.templateId());
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
    public static class GeneratePptResourceRequest {
        @Description("源学习资源ID，必须是当前用户学习空间中的资源")
        private Long sourceResourceId;
        @Description("学习空间ID")
        private Long spaceId;
        @Description("PPT主题，未传时会使用源资源标题")
        private String topic;
        @Description("模板风格，例如 education、business、simple；未传时使用随机模板")
        private String templateStyle;
        @Description("目标页数，建议 5 到 20 页")
        private Integer targetSlides;
        @Description("学习阶段序号，从1开始")
        private Integer phaseIndex;
    }
}
