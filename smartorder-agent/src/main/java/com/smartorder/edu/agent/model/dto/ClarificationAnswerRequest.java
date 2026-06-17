package com.smartorder.edu.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 澄清回答请求——专用 API，不混入 /agent/chat/stream
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClarificationAnswerRequest {

    @NotBlank(message = "回答内容不能为空")
    private String answer;

    /**
     * 用户可见的展示值（SELECT 类型时为选项 label，TEXT 类型时与 answer 相同）。
     * 用于持久化到对话记录中，确保刷新后仍显示可读文本。
     */
    private String displayAnswer;

    /** 可选：关联的会话 ID，用于 SSE 事件上下文 */
    private String conversationId;
}
