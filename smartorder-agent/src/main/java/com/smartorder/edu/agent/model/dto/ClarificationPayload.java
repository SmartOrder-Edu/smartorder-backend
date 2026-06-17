package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化澄清信息 —— PLAN_PAUSED SSE 事件 payload 中的 clarification 字段
 * <p>
 * 前端收到后据此渲染对应类型的输入控件（文本框 / 单选卡 / 多选卡 / 数字框）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClarificationPayload {

    /** 澄清记录唯一 ID */
    private String clarificationId;

    /** 槽位标识 (如 target_scope, current_level) */
    private String slotKey;

    /** 向用户提出的问题 */
    private String question;

    /** 答案类型：TEXT | SINGLE_SELECT | MULTI_SELECT | NUMBER */
    private String answerType;

    /** 选项列表（仅 SELECT 类型有值） */
    private List<SelectOption> options;

    /** 槽位优先级（数字越小越先问） */
    private int slotPriority;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SelectOption {
        private String label;
        private String value;
    }
}
