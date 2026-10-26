package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.util.PromptLoader;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 会话附件检索工具——允许 Agent 在本轮附件上下文不足时主动补查附件内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchConversationAttachmentsTool implements ToolHandler {

    private static final int DEFAULT_TOP_K = 6;
    private static final int MAX_TOP_K = 10;

    private final AgentConversationAttachmentService attachmentService;
    private final PromptLoader promptLoader;

    @Override
    public String name() {
        return "searchConversationAttachments";
    }

    @Override
    public String description() {
        return "检索当前会话中用户已上传并指定的附件内容。"
                + "当用户询问附件、文档、课件、PDF、文件内容，或当前附件摘录不足以回答时使用。"
                + "工具只会检索当前会话允许访问的 ready 附件，返回可用于回答的附件片段和引用编号。";
    }

    @Override
    public Class<?> requestType() {
        return SearchConversationAttachmentsRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SEARCH;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        SearchConversationAttachmentsRequest req = (SearchConversationAttachmentsRequest) request;
        String query = req.getQuery();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (context.getRuntimeContext() == null) {
            throw new IllegalStateException("缺少 Agent 运行时上下文，无法限定附件检索范围");
        }

        List<Long> allowedFileIds = context.getRuntimeContext().getAllowedAttachmentFileIds();
        if (allowedFileIds == null || allowedFileIds.isEmpty()) {
            throw new IllegalStateException("当前对话没有可检索的附件，请先让用户上传并等待解析完成");
        }

        List<Long> fileIds = resolveFileIds(req.getFileIds(), allowedFileIds);
        int topK = req.getTopK() == null ? DEFAULT_TOP_K : Math.max(1, Math.min(req.getTopK(), MAX_TOP_K));

        log.info("[SearchConversationAttachments] conversationId={}, fileIds={}, topK={}, reason={}",
                context.getConversationId(), fileIds, topK, req.getReason());

        String contextText = attachmentService.buildRagContext(
                context.getUserId(), context.getConversationId(), fileIds, query, topK);

        boolean hasEnoughEvidence = contextText != null
                && !contextText.isBlank()
                && !contextText.contains("未找到与本轮问题足够相关的附件片段");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("fileIds", fileIds);
        result.put("hasEnoughEvidence", hasEnoughEvidence);
        result.put("contextText", contextText);
        result.put("instruction", promptLoader.getCitationInstruction());
        return result;
    }

    private List<Long> resolveFileIds(List<Long> requestedFileIds, List<Long> allowedFileIds) {
        LinkedHashSet<Long> allowed = new LinkedHashSet<>(allowedFileIds);
        if (requestedFileIds == null || requestedFileIds.isEmpty()) {
            return List.copyOf(allowed);
        }

        LinkedHashSet<Long> requested = new LinkedHashSet<>(requestedFileIds);
        for (Long fileId : requested) {
            if (!allowed.contains(fileId)) {
                throw new SecurityException("附件不在当前对话允许检索范围内: " + fileId);
            }
        }
        return List.copyOf(requested);
    }

    @Data
    public static class SearchConversationAttachmentsRequest {
        @dev.langchain4j.model.output.structured.Description("用于检索附件的查询语句，必须结合用户问题改写为清晰关键词或问题")
        private String query;

        @dev.langchain4j.model.output.structured.Description("要检索的附件文件ID列表。可省略，省略时检索当前会话本轮允许的全部附件")
        private List<Long> fileIds;

        @dev.langchain4j.model.output.structured.Description("返回的附件片段数量，默认6，最大10")
        private Integer topK;

        @dev.langchain4j.model.output.structured.Description("调用此工具的原因，用一句话说明为什么需要检索附件")
        private String reason;
    }
}
