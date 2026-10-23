package com.smartorder.edu.common.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体类
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
@NoArgsConstructor
public class User {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 用户名/昵称
     */
    private String username;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 手机号
     */
    private String phone;
    /**
     * 密码（BCrypt加密）
     */
    private String password;
    /**
     * 头像URL
     */
    private String avatarUrl;
    /**
     * 性别：0=未设置，1=男，2=女
     */
    private Integer gender;
    /**
     * 生日
     */
    private LocalDate birthday;
    /**
     * 个人简介
     */
    private String bio;
    /**
     * 角色：0=学生，1=管理员
     */
    private Integer role;
    /**
     * VIP等级：0=普通用户
     */
    private Integer vipLevel;
    /**
     * VIP开始时间
     */
    private LocalDateTime vipStartTime;
    /**
     * VIP到期时间
     */
    private LocalDateTime vipExpireTime;
    /**
     * 账号状态：0=禁用，1=正常
     */
    private Integer status;
    /**
     * 逻辑删除：0=正常，1=已删除
     */
    private Integer isDeleted;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    /**
     * GitHub 用户唯一标识
     */
    private String githubId;
    /**
     * GitHub 用户名
     */
    private String githubUsername;
    /**
     * Gitee 用户唯一标识
     */
    private String giteeId;
    /**
     * Gitee 用户名
     */
    private String giteeUsername;
}
