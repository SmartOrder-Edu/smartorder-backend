package com.smartorder.edu.agent.model.enums;

/**
 * 路由来源——标识路由决策由哪一层做出
 */
public enum RoutingSource {
    /** 状态驱动：会话状态（待确认草稿等）直接决定路由 */
    STATE,
    /** 关键词规则：高置信关键词白名单匹配 */
    KEYWORD,
    /** LLM 分类：调用 LLM 进行语义级别的意图分类 */
    LLM,
    /** 澄清兜底：低置信或槽位不足，进入追问澄清 */
    CLARIFICATION,
    /** 用户手动选择：前端强制指定运行模式，跳过意图路由 */
    USER_SELECTED
}
