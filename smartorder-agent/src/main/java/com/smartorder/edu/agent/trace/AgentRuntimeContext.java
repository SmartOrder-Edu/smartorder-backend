package com.smartorder.edu.agent.trace;

import com.smartorder.edu.agent.route.AgentRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 运行时上下文——一次完整 Agent 调用的生命周期状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeContext {

    private String traceId;

    private String conversationId;

    private Long userId;

    /**
     * 用户原始消息
     */
    private String message;

    @Builder.Default
    private AgentRunMode mode = AgentRunMode.REACT;

    private String attachmentContext;

    /**
     * RAG 检索上下文（自动触发时生成）
     */
    private String ragContext;

    /**
     * 是否强制调用 ragRetrieve 工具（当前端传递了 fileIds 或 knowledgeBaseIds 时为 true）
     */
    private boolean forceRagRetrieve;

    /**
     * 前端传递的知识库ID列表（用于 RAG 检索）
     */
    private List<Long> knowledgeBaseIds;

    /**
     * 前端传递的文件ID列表（用于 RAG 检索）
     */
    private List<Long> fileIds;

    private List<Long> allowedAttachmentFileIds;

    @Builder.Default
    private AtomicInteger sequence = new AtomicInteger(0);

    /**
     * 获取下一个事件序号
     */
    public int nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 当前序号（不自增）
     */
    public int currentSeq() {
        return sequence.get();
    }
}
