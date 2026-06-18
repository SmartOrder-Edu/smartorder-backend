package com.smartorder.edu.agent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化澄清回答 —— 前端提交澄清答案时的请求体
 */
@Data
@NoArgsConstructor
public class ClarificationAnswer {

    /** 澄清记录唯一 ID */
    private String clarificationId;

    /** 用户的回答（文本或选中值的 JSON） */
    private String answer;
}
