package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * 修改密码DTO对象
 * @author 谢光湘
 * @since 2026/3/7
 */
@Data
public class ChangePasswordDTO {
    /**
     * 新密码
     */
    private String newPassword;
    /**
     * 确认新密码
     */
    private String confirmPassword;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 验证码
     */
    private Integer verificationCode;
}
