package com.smartorder.edu.common.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方身份绑定视图（不含敏感字段如 accessToken）
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Data
public class AuthIdentityVO {

    /**
     * 第三方平台标识：github/gitee
     */
    private String providerCode;

    /**
     * 第三方平台用户名
     */
    private String providerUsername;

    /**
     * 第三方平台邮箱
     */
    private String providerEmail;

    /**
     * 邮箱是否已验证：0=未验证，1=已验证
     */
    private Integer emailVerified;

    /**
     * 绑定状态：0=禁用，1=正常
     */
    private Integer status;

    /**
     * 首次绑定/登录时间
     */
    private LocalDateTime firstLoginTime;

    /**
     * 最近登录时间
     */
    private LocalDateTime lastLoginTime;
}
