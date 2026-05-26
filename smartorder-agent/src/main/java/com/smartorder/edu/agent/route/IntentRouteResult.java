package com.smartorder.edu.agent.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图路由结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentRouteResult {

    /** 意图类型（中文，兼容旧代码） */
    private String intentType;

    /** 意图类型（枚举） */
    private IntentType intentEnum;

    /** 复杂度 */
    private String complexity;

    /** 运行模式 */
    private AgentRunMode runMode;

    /** 需要的工具列表 */
    private List<String> requiredTools;

    /** 路由原因 */
    private String reason;

    // ========== 新增字段（Phase 0+） ==========

    /** 置信度 0.0 ~ 1.0 */
    @Builder.Default
    private double confidence = 1.0;

    /** 路由来源 */
    @Builder.Default
    private RoutingSource source = RoutingSource.KEYWORD;

    /** 是否需要向用户追问澄清 */
    @Builder.Default
    private boolean needsClarification = false;

    /** 缺失的槽位列表 */
    @Builder.Default
    private List<String> missingSlots = List.of();
}
