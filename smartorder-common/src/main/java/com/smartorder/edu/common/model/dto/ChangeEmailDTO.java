package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * 修改邮箱请求 DTO
 *
 * @author 马强
 * @since 2026-07-15
 */
@Data
public class ChangeEmailDTO {
    /** 新邮箱 */
    private String newEmail;
    /** 邮箱验证码 */
    private String verificationCode;
}
