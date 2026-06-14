package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
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

    private final ChatModel chatLanguageModel;
    private final AgentResourceLogMapper resourceLogMapper;
    private final PromptLoader promptLoader;

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

        // 调用 LLM 生成资源内容
        String prompt = buildResourcePrompt(req.getTopic(), req.getResourceType());
        String content = chatLanguageModel.chat(prompt);

        // 记录资源生成日志
        AgentResourceLog resourceLog = new AgentResourceLog();
        resourceLog.setSpaceId(req.getSpaceId());
        resourceLog.setPhaseIndex(req.getPhaseIndex() != null ? req.getPhaseIndex() : 1);
        resourceLog.setResourceType(req.getResourceType() != null ? req.getResourceType() : "DOC");
        resourceLog.setTitle(req.getTopic() + " - " + (req.getResourceType() != null ? req.getResourceType() : "学习资料"));
        resourceLog.setStatus("COMPLETED");
        resourceLog.setResultUrl(null); // 文本资源暂不存储文件URL
        resourceLog.setGenerationParams("{\"topic\":\"" + req.getTopic() + "\",\"type\":\"" + req.getResourceType() + "\"}");
        resourceLog.setUserId(userId);
        resourceLogMapper.insert(resourceLog);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceLog.getId());
        result.put("title", resourceLog.getTitle());
        result.put("resourceType", resourceLog.getResourceType());
        result.put("content", content);
        result.put("message", "资源已生成并记录");
        return result;
    }

    private String buildResourcePrompt(String topic, String resourceType) {
        String typeName = resourceType != null ? resourceType : "学习资料";
        return PromptLoader.format(promptLoader.getResourceGenTemplate(), typeName, topic);
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
