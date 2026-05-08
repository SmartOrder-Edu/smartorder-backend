package com.smartorder.edu.common.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户数据传输对象
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
@NoArgsConstructor
public class UserDTO {
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
     * 教学领域
     */
    private String teachingField;
}
