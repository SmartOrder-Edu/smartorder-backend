package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * GitHub 登录 DTO 对象
 * @author Maq
 * @since 2026/07/12
 */
@Data
public class GithubLoginDTO {
    /**
     * GitHub OAuth 授权 code
     */
    private String code;
    /**
     * GitHub OAuth state，用于防范 CSRF 攻击
     */
    private String state;
}
