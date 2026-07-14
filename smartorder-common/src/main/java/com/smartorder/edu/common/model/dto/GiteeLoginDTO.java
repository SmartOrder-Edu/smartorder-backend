package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * Gitee 登录 DTO 对象
 * @author Maq
 * @since 2026/07/14
 */
@Data
public class GiteeLoginDTO {
    /**
     * Gitee OAuth 授权 code
     */
    private String code;
    /**
     * Gitee OAuth state，用于防范 CSRF 攻击
     */
    private String state;
}
