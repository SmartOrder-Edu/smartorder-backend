package com.smartorder.edu.agent.rag.router;

import com.smartorder.edu.agent.rag.model.QueryAnalysisResult;
import com.smartorder.edu.agent.rag.model.RetrievalRouteResult;

/**
 * 检索路由器接口
 * 根据查询分析结果决定使用哪种检索模式
 */
public interface RetrievalRouter {

    /**
     * 路由决策
     *
     * @param analysisResult 查询分析结果
     * @param requestedMode  用户请求的检索模式（auto/simple/medium/complex）
     * @return 路由结果
     */
    RetrievalRouteResult route(QueryAnalysisResult analysisResult, String requestedMode);
}
