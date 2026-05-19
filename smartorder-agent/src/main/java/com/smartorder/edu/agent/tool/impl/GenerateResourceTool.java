package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
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
        return String.format("""
                你是一位专业的教育资源设计师。请为以下知识点生成一份%s：

                知识点：%s

                要求：
                1. 内容结构清晰，分点阐述
                2. 包含核心概念解释
                3. 提供典型例题或应用场景
                4. 总结关键要点
                5. 语言通俗易懂，适合大学生阅读

                请直接输出内容，使用 Markdown 格式。
                """, typeName, topic);
    }

    @Data
    public static class GenerateResourceRequest {
        private Long spaceId;
        private String topic;
        private String resourceType;
        private Integer phaseIndex;
    }
}
