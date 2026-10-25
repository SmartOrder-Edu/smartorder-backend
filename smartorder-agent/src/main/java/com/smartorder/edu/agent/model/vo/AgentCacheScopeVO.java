package com.smartorder.edu.agent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端本地缓存的隔离范围（{@code GET /agent/tasks/cache-scope}）。
 * <p>
 * 由已校验 JWT 的租户与用户拼接，前端用它作为 IndexedDB 的库名前缀，
 * 切换账号或租户后不会读到上一个身份的缓存。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "本地缓存隔离范围")
public class AgentCacheScopeVO {

    @Schema(description = "缓存隔离键，格式 租户:用户ID")
    private String scopeKey;

    /** 同一身份每次调用结果稳定，便于前端做变更检测。 */
    public static AgentCacheScopeVO of(String tenantId, Long userId) {
        return new AgentCacheScopeVO(tenantId + ":" + userId);
    }
}
