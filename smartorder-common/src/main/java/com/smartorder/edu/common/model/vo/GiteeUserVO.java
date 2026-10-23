package com.smartorder.edu.common.model.vo;

import lombok.Data;

/**
 * Gitee 用户信息响应对象
 * @author Maq
 * @since 2026/07/14
 */
@Data
public class GiteeUserVO {
    /**
     * Gitee 用户的唯一标识 id
     */
    private String id;
    /**
     * Gitee 登录名 login
     */
    private String login;
    /**
     * Gitee 昵称 name
     */
    private String name;
    /**
     * Gitee 头像地址 avatar_url
     */
    private String avatarUrl;
    /**
     * Gitee 用户邮箱 email
     */
    private String email;
    /**
     * Gitee OAuth access_token（用于防重放校验，仅服务端内部使用，不返回给前端）
     */
    private String accessToken;
}
