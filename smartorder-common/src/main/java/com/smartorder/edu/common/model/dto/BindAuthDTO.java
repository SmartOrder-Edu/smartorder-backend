package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * 第三方账号绑定请求
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Data
public class BindAuthDTO {

    /**
     * 第三方平台标识：github/gitee
     */
    private String providerCode;

    /**
     * OAuth 授权码（从第三方回调获取）
     */
    private String code;

    /**
     * CSRF 防护 state 参数
     */
    private String state;
}
