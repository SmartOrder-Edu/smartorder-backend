package com.smartorder.edu.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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
}
