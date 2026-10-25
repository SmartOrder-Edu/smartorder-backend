package com.smartorder.edu.agent.common;

import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 当前请求的调用者身份（用户 + 租户）。
 * <p>
 * 由已校验的 JWT 解析得出，任何业务层都不得接受客户端自报的租户或用户标识。
 * 统一封装可避免每个接口各写一遍取值逻辑，也避免漏传租户导致跨租户读到数据。
 *
 * @param userId   当前登录用户 ID
 * @param tenantId 令牌携带的租户标识，缺省为 {@value #DEFAULT_TENANT}
 */
public record AgentCaller(Long userId, String tenantId) {

    /** 令牌未携带租户时的默认隔离域。 */
    public static final String DEFAULT_TENANT = "default";

    /** 从 JWT 声明解析调用者；声明缺失直接拒绝，不降级为匿名访问。 */
    public static AgentCaller from(Claims claims) {
        if (claims == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Long userId = claims.get("userId", Long.class);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户信息缺失");
        }
        Object tenant = claims.get("tenantId");
        return new AgentCaller(userId, tenant == null ? DEFAULT_TENANT : String.valueOf(tenant));
    }
}
