package com.smartorder.edu.common.model.vo;

import lombok.Data;

/**
 * GitHub 用户信息响应对象
 * @author Maq
 * @since 2026/07/12
 */
@Data
public class GithubUserVO {
    /**
     * GitHub 用户的唯一标识 id
     */
    private String id;
    /**
     * GitHub 登录名 login
     */
    private String login;
    /**
     * GitHub 昵称 name
     */
    private String name;
    /**
     * GitHub 头像地址 avatar_url
     */
    private String avatarUrl;
    /**
     * GitHub 用户邮箱 email
     */
    private String email;
    /**
     * GitHub OAuth access_token（用于防重放校验，仅服务端内部使用，不返回给前端）
     */
    private String accessToken;
}
