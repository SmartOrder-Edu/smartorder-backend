package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.IntentRouteResult;
import com.smartorder.edu.agent.model.dto.RouteContext;

/**
 * 意图路由服务——分层路由编排器
 * <p>
 * 路由优先级：状态检查 → 关键词规则 → LLM 分类（Phase 3）→ 槽位校验（Phase 4）→ 澄清兜底（Phase 5）
 */
public interface IntentRouteService {

    IntentRouteResult route(RouteContext ctx);

    IntentRouteResult route(String message);
}
