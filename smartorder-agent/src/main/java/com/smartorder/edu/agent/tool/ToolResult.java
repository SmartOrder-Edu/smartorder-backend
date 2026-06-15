package com.smartorder.edu.agent.tool;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结构化返回——替代直接抛异常的失败模式
 * <p>
 * 工具成功时返回 {@link #success(Object)}，失败时返回 {@link #error(String)}。
 * 失败时不会阻断 ReAct 循环，而是将错误信息作为上下文反馈给 LLM，
 * 让 LLM 有机会调整策略（换工具、简化参数、跳过步骤等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    /** 是否执行成功 */
    private boolean success;

    /** 成功时的结果数据 */
    private Object data;

    /** 失败时的用户可读错误消息 */
    private String errorMessage;

    /** 失败时的调试信息（不发给用户，仅日志） */
    private String debugInfo;

    /** 工具名称 */
    private String toolName;

    /**
     * 创建成功结果
     */
    public static ToolResult success(Object data) {
        return ToolResult.builder()
                .success(true)
                .data(data)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static ToolResult error(String message) {
        return ToolResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }

    /**
     * 创建带调试信息的失败结果
     */
    public static ToolResult error(String message, String debugInfo) {
        return ToolResult.builder()
                .success(false)
                .errorMessage(message)
                .debugInfo(debugInfo)
                .build();
    }

    /**
     * 转换为 LLM 可理解的上下文文本
     * <p>
     * 成功时返回数据 JSON，失败时返回用户可读的错误提示 + 建议动作。
     * 注意：errorMessage 必须是面向 LLM 的自然语言描述，不含内部工具名、异常类名等实现细节。
     * 内部调试信息由 debugInfo 字段承载，仅记录日志，不发送给 LLM。
     */
    public String toLlmContext() {
        if (success) {
            if (data instanceof String s) {
                return s;
            }
            return JSON.toJSONString(data);
        }
        // errorMessage 已是面向 LLM 的自然语言，直接使用
        return errorMessage + " 请尝试其他方法或跳过此步骤。";
    }
}
