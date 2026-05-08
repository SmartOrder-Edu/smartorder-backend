package com.smartorder.edu.common.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录数据传输对象
 * @author 谢光湘
 * @since 2026/2/26
 */

@Data
@NoArgsConstructor
@Schema(description="用户登录请求参数")
public class LoginDTO {
    /**
     * 邮箱
     */
    @Schema(description = "邮箱", required = true, example = "user@example.com")
    private String email;
    /**
     * 手机号
     */
    @Schema(description = "手机号", required = true, example = "13800000000")
    private String phone;
    /**
     * 密码
     */
    @Schema(description = "密码", required = true, example = "abc123456")
    private String password;
    /**
     * IP地址
     */
    private String ip;

    /**
     * 身份（0：学生，1：教师，2：管理员）
     */
    private String role;

}
