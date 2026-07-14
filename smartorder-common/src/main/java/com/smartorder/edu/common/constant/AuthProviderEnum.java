package com.smartorder.edu.common.constant;

import lombok.Getter;

/**
 * 第三方登录平台枚举
 * @author Ma Qiang
 * @since 2026/7/14
 */
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
