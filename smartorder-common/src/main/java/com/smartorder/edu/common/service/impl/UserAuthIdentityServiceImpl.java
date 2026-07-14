package com.smartorder.edu.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.mapper.UserAuthIdentityMapper;
import com.smartorder.edu.common.model.entity.UserAuthIdentity;
import com.smartorder.edu.common.model.vo.AuthIdentityVO;
import com.smartorder.edu.common.service.IUserAuthIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.util.validation.ValidationException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户第三方登录身份服务实现
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthIdentityServiceImpl implements IUserAuthIdentityService {

    private final UserAuthIdentityMapper userAuthIdentityMapper;

    @Override
    public UserAuthIdentity bindIdentity(Long userId, String providerCode, String providerSubject,
                                         String providerUsername, String providerEmail, boolean emailVerified,
                                         String accessToken, String refreshToken, LocalDateTime tokenExpireTime, String scope) {
        if (userId == null) {
            throw new ValidationException("用户ID不能为空");
        }
        if (providerCode == null || providerCode.isBlank()) {
            throw new ValidationException("第三方平台标识不能为空");
        }
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new ValidationException("第三方平台用户ID不能为空");
        }

        // 检查是否已绑定该平台（不过滤 status，防止软删除后重新绑定 UK 冲突）
        UserAuthIdentity existing = userAuthIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserAuthIdentity>()
                        .eq(UserAuthIdentity::getUserId, userId)
                        .eq(UserAuthIdentity::getProviderCode, providerCode)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            // 已存在则更新信息（含软删除恢复场景）
            log.info("用户 {} 的 {} 绑定已存在（status={}），更新身份信息", userId, providerCode, existing.getStatus());
            existing.setProviderSubject(providerSubject);
            existing.setProviderUsername(providerUsername);
            if (providerEmail != null) {
                existing.setProviderEmail(providerEmail);
            }
            existing.setEmailVerified(emailVerified ? 1 : 0);
            existing.setAccessToken(accessToken);
            existing.setRefreshToken(refreshToken);
            existing.setTokenExpireTime(tokenExpireTime);
            existing.setScope(scope);
            existing.setStatus(1);
            // 如果是软删除恢复，重置 firstLoginTime
            if (existing.getFirstLoginTime() == null) {
                existing.setFirstLoginTime(LocalDateTime.now());
            }
            existing.setLastLoginTime(LocalDateTime.now());
            userAuthIdentityMapper.updateById(existing);
            return existing;
        }

        // 新建绑定
        UserAuthIdentity identity = new UserAuthIdentity();
        identity.setUserId(userId);
        identity.setProviderCode(providerCode);
        identity.setProviderSubject(providerSubject);
        identity.setProviderUsername(providerUsername);
        identity.setProviderEmail(providerEmail);
        identity.setEmailVerified(emailVerified ? 1 : 0);
        identity.setAccessToken(accessToken);
        identity.setRefreshToken(refreshToken);
        identity.setTokenExpireTime(tokenExpireTime);
        identity.setScope(scope);
        identity.setStatus(1);
        identity.setFirstLoginTime(LocalDateTime.now());
        identity.setLastLoginTime(LocalDateTime.now());

        userAuthIdentityMapper.insert(identity);
        log.info("用户 {} 成功绑定 {} 身份: {}", userId, providerCode, providerUsername);
        return identity;
    }

    @Override
    public void unbindIdentity(Long userId, String providerCode) {
        if (userId == null) {
            throw new ValidationException("用户ID不能为空");
        }
        if (providerCode == null || providerCode.isBlank()) {
            throw new ValidationException("第三方平台标识不能为空");
        }

        UserAuthIdentity identity = getByUserAndProvider(userId, providerCode);
        if (identity == null) {
            throw new BusinessException("该平台未绑定，无需解绑");
        }

        // 软删除：设置 status=0
        UpdateWrapper<UserAuthIdentity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("user_id", userId)
                .eq("provider_code", providerCode)
                .set("status", 0);
        userAuthIdentityMapper.update(null, updateWrapper);
        log.info("用户 {} 成功解绑 {} 身份", userId, providerCode);
    }

    @Override
    public List<AuthIdentityVO> listIdentities(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }

        List<UserAuthIdentity> identities = userAuthIdentityMapper.selectList(
                new LambdaQueryWrapper<UserAuthIdentity>()
                        .eq(UserAuthIdentity::getUserId, userId)
                        .eq(UserAuthIdentity::getStatus, 1)
        );

        return identities.stream().map(identity -> {
            AuthIdentityVO vo = new AuthIdentityVO();
            vo.setProviderCode(identity.getProviderCode());
            vo.setProviderUsername(identity.getProviderUsername());
            vo.setProviderEmail(identity.getProviderEmail());
            vo.setEmailVerified(identity.getEmailVerified());
            vo.setStatus(identity.getStatus());
            vo.setFirstLoginTime(identity.getFirstLoginTime());
            vo.setLastLoginTime(identity.getLastLoginTime());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public UserAuthIdentity getByProviderSubject(String providerCode, String providerSubject) {
        if (providerCode == null || providerSubject == null) {
            return null;
        }
        return userAuthIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserAuthIdentity>()
                        .eq(UserAuthIdentity::getProviderCode, providerCode)
                        .eq(UserAuthIdentity::getProviderSubject, providerSubject)
                        .eq(UserAuthIdentity::getStatus, 1)
        );
    }

    @Override
    public UserAuthIdentity getByUserAndProvider(Long userId, String providerCode) {
        if (userId == null || providerCode == null) {
            return null;
        }
        return userAuthIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserAuthIdentity>()
                        .eq(UserAuthIdentity::getUserId, userId)
                        .eq(UserAuthIdentity::getProviderCode, providerCode)
                        .eq(UserAuthIdentity::getStatus, 1)
        );
    }

    @Override
    public void updateIdentity(UserAuthIdentity identity) {
        if (identity == null || identity.getId() == null) {
            throw new ValidationException("身份记录不能为空");
        }
        userAuthIdentityMapper.updateById(identity);
        log.debug("更新身份记录: id={}, provider={}", identity.getId(), identity.getProviderCode());
    }

    @Override
    public boolean hasOtherLoginMethod(Long userId, String excludeProviderCode, boolean hasPassword) {
        // 有密码 → 有其它登录方式
        if (hasPassword) {
            return true;
        }

        // 检查是否还有其他有效的第三方绑定
        Long count = userAuthIdentityMapper.selectCount(
                new LambdaQueryWrapper<UserAuthIdentity>()
                        .eq(UserAuthIdentity::getUserId, userId)
                        .eq(UserAuthIdentity::getStatus, 1)
                        .ne(UserAuthIdentity::getProviderCode, excludeProviderCode)
        );
        return count > 0;
    }
}
