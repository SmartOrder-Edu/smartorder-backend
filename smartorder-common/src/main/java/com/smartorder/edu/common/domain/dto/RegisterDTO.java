package com.smartorder.edu.common.domain.dto;


import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册DTO对象
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
@NoArgsConstructor
public class RegisterDTO {
    /**
     * 邮箱
     */
    private String email;
    /**
     * 手机号
     */
    private String phone;
    /**
     * 密码
     */
    private String password;
    /**
     * 确认密码
     */
    private String confirmPassword;
    /**
     * 验证码
     */
    private String verificationCode;
}

