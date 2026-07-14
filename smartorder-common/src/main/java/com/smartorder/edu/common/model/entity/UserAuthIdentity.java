package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户第三方登录身份表
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Data
@NoArgsConstructor
@TableName("user_auth_identity")
public class UserAuthIdentity {

    /**
     * 主键ID（自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联本地 user.id
     */
    private Long userId;

    /**
     * 第三方平台标识：github/gitee/google/baidu
     */
    private String providerCode;

    /**
     * 第三方平台稳定用户ID
     */
    private String providerSubject;

    /**
     * 第三方平台用户名
     */
    private String providerUsername;

    /**
     * 第三方平台返回邮箱
     */
    private String providerEmail;

    /**
     * 第三方邮箱是否已验证：0=未验证，1=已验证
     */
    private Integer emailVerified;

    /**
     * 第三方访问令牌，加密保存
     */
    private String accessToken;

    /**
     * 第三方刷新令牌，加密保存
     */
    private String refreshToken;

    /**
     * 访问令牌过期时间
     */
    private LocalDateTime tokenExpireTime;

    /**
     * 第三方授权范围
     */
    private String scope;

    /**
     * 身份状态：0=禁用，1=正常
     */
    private Integer status;

    /**
     * 首次使用该身份登录时间
     */
    private LocalDateTime firstLoginTime;

    /**
     * 最近使用该身份登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
