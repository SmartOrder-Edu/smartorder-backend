package com.smartorder.edu.common.domain.vo;

import lombok.Data;

/**
 * 用户视图对象
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
public class UserVO {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 用户名
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
     * 头像URL
     */
    private String avatarUrl;
    /**
     * 性别
     */
    private Integer gender;
    /**
     * 生日
     */
    private String birthday;
    /**
     * 个人简介
     */
    private String bio;
     /**
     * 角色
     */
    private Integer role;
    /**
     * 教学领域，教师用户特有属性，表示教师的教学领域，如语文、数学、英语等
     */
    private String teachingField;
    /**
     * vip等级
     */
    private Integer vipLevel;
    /**
     * vip开始时间
     */
    private String vipStartTime;
    /**
     * vip到期时间
     */
    private String vipExpireTime;
}

