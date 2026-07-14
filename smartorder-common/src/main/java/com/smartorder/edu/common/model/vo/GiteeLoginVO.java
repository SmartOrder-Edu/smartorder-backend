package com.smartorder.edu.common.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gitee 登录响应 VO
 * <p>
 * 封装登录成功后的 accessToken 以及新老用户标识，
 * 前端根据 {@code isNewUser} 判断是否需要展示新用户引导界面。
 * </p>
 *
 * @author Maq
 * @since 2026/07/14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GiteeLoginVO {
    /** JWT 访问令牌 */
    private String accessToken;
    /** 是否为新用户（本次登录时自动创建的账号） */
    private Boolean isNewUser;
}
