package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索路由结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRouteResult {

    /**
     * 选择的检索模式：simple/medium/complex
     */
    private String retrievalMode;

    /**
     * 路由置信度 0-1
     */
    private double confidence;

    /**
     * 路由原因
     */
    private String reason;

    /**
     * 是否为降级路由
     */
    private boolean degraded;

    /**
     * 原始检索模式（降级前）
     */
    private String originalMode;
}
