package com.smartorder.edu.manage.domain.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 管理端用户列表视图对象，避免向后台页面暴露密码等敏感字段。
 */
@Data
public class AdminUserVO {

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
     * 头像地址
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
     * 用户角色：0=学生，1=管理员，2=教师
     */
    private Integer role;

    /**
     * VIP等级：0=普通用户
     */
    private Integer vipLevel;

    /**
     * 账号状态：0=禁用，1=正常
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最近更新时间
     */
    private LocalDateTime updateTime;
}
