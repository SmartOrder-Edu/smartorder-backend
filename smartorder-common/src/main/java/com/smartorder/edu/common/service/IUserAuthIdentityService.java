package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.entity.UserAuthIdentity;
import com.smartorder.edu.common.model.vo.AuthIdentityVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户第三方登录身份服务
 * @author Ma Qiang
 * @since 2026/7/14
 */
public interface IUserAuthIdentityService {

    /**
     * 绑定第三方身份到用户
     *
     * @param userId          本地用户ID
     * @param providerCode    第三方平台标识
     * @param providerSubject 第三方平台用户唯一标识
     * @param providerUsername 第三方平台用户名
     * @param providerEmail   第三方平台邮箱
     * @param emailVerified   邮箱是否已验证
     * @param accessToken     访问令牌
     * @param refreshToken    刷新令牌
     * @param tokenExpireTime 令牌过期时间
     * @param scope           授权范围
     * @return 绑定后的身份记录
     */
    UserAuthIdentity bindIdentity(Long userId, String providerCode, String providerSubject,
                                  String providerUsername, String providerEmail, boolean emailVerified,
                                  String accessToken, String refreshToken, LocalDateTime tokenExpireTime, String scope);

    /**
     * 解绑指定平台的第三方身份
     *
     * @param userId       本地用户ID
     * @param providerCode 第三方平台标识
     */
    void unbindIdentity(Long userId, String providerCode);

    /**
     * 获取用户所有已绑定的第三方身份列表（不含敏感字段）
     *
     * @param userId 本地用户ID
     * @return 绑定身份列表
     */
    List<AuthIdentityVO> listIdentities(Long userId);

    /**
     * 根据平台+第三方用户ID查找身份记录
     *
     * @param providerCode    第三方平台标识
     * @param providerSubject 第三方平台用户唯一标识
     * @return 身份记录，不存在返回 null
     */
    UserAuthIdentity getByProviderSubject(String providerCode, String providerSubject);

    /**
     * 根据用户+平台查找身份记录
     *
     * @param userId       本地用户ID
     * @param providerCode 第三方平台标识
     * @return 身份记录，不存在返回 null
     */
    UserAuthIdentity getByUserAndProvider(Long userId, String providerCode);

    /**
     * 更新身份记录（如登录时间、用户名变更等）
     *
     * @param identity 身份记录
     */
    void updateIdentity(UserAuthIdentity identity);

    /**
     * 检查用户是否有其他登录方式（密码或其它绑定）
     *
     * @param userId             本地用户ID
     * @param excludeProviderCode 排除的平台（解绑场景）
     * @param hasPassword        用户是否设置了密码
     * @return true=有其他登录方式
     */
    boolean hasOtherLoginMethod(Long userId, String excludeProviderCode, boolean hasPassword);
}
