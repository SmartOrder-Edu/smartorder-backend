package com.smartorder.edu.common.constant;

import lombok.Getter;

/**
 * 第三方登录平台枚举
 *
 * @deprecated 全仓无任何引用。第三方登录改造为 {@code user_auth_identity} 表后，
 * provider 统一以字符串常量（{@code "github"} / {@code "gitee"}）写入
 * {@code UserAuthIdentity.provider}，由 {@code AuthServiceImpl} 直接传值，
 * 不再经过枚举转换。本类可在确认后删除。
 *
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Deprecated(since = "2.0", forRemoval = false)
@Getter
public enum AuthProviderEnum {

    GITHUB("github", "GitHub"),
    GITEE("gitee", "Gitee");

    private final String code;
    private final String displayName;

    AuthProviderEnum(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 根据 providerCode 获取枚举
     */
    public static AuthProviderEnum fromCode(String code) {
        for (AuthProviderEnum provider : values()) {
            if (provider.getCode().equalsIgnoreCase(code)) {
                return provider;
            }
        }
        return null;
    }
}
