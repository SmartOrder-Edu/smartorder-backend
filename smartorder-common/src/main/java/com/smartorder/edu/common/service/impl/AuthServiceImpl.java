package com.smartorder.edu.common.service.impl;


import com.smartorder.edu.common.model.dto.ChangePasswordDTO;
import com.smartorder.edu.common.model.dto.LoginDTO;
import com.smartorder.edu.common.model.dto.RegisterDTO;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.exception.ValidationException;
import com.smartorder.edu.common.service.IAuthService;
import com.smartorder.edu.common.service.IUserService;
import com.smartorder.edu.common.util.EmailVerificationCodeUtils;
import com.smartorder.edu.common.util.JwtTokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现类，处理用户登录、注册等操作
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {
    /**
     * 用户服务接口
     */
    private final IUserService userService;
    /**
     * 密码加密器
     */
    private final BCryptPasswordEncoder passwordEncoder;
    /**
     * 邮箱验证码工具类
     */
    private final EmailVerificationCodeUtils emailVerificationCodeUtils;
    /**
     * Redis模板，用于操作Redis缓存
     */
    private final StringRedisTemplate stringRedisTemplate;

    private static final String REDIS_KEY_PREFIX = "refreshToken:";


    @Override
    public void register(RegisterDTO registerDTO) {
        // 检验参数
        String username = registerDTO.getUsername();
        String email = registerDTO.getEmail();
        String phone = registerDTO.getPhone();
        String rawPassword = registerDTO.getPassword();
        String confirmPassword = registerDTO.getConfirmPassword();
        String verificationCode = registerDTO.getVerificationCode();

        // 昵称校验
        if (!StringUtils.hasText(username)) {
            throw new ValidationException("昵称不能为空");
        }
        if (username.length() < 2 || username.length() > 20) {
            throw new ValidationException("昵称长度需在2-20个字符之间");
        }

        boolean isEmailRegister = StringUtils.hasText(email);
        boolean isPhoneRegister = StringUtils.hasText(phone);
        if(!isEmailRegister && !isPhoneRegister){
            throw new ValidationException("邮箱或手机号不能为空");
        }
        String passwordRegex = "^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z]).{8,}$";
        if (!StringUtils.hasText(rawPassword) || !rawPassword.matches(passwordRegex)) {
            throw new ValidationException("密码格式错误，密码至少8位，包含大小写字母和数字");
        }
        if(!rawPassword.equals(confirmPassword)){
            throw new ValidationException("两次输入的密码不一致");
        }
        //验证验证码
        if(!StringUtils.hasText(verificationCode)){
            throw new ValidationException("验证码不能为空");
        }
        // 验证验证码
        if(isEmailRegister){
            emailVerificationCodeUtils.verifyCode(email, verificationCode);
        }else{
            // TODO: 验证手机号验证码
            throw new BusinessException("手机号注册暂未支持");
        }
        // 密码加密
        String encodedPassword = encodePassword(rawPassword);
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(encodedPassword);
        // 保存用户
        userService.insertUser(user);
    }

    @Override
    public Map<String,String> login(LoginDTO loginDTO) {
        String email = loginDTO.getEmail();
        String phone = loginDTO.getPhone();
        String rawPassword = loginDTO.getPassword();

        if (!StringUtils.hasText(rawPassword)) {
            throw new ValidationException("密码不能为空");
        }

        User user = null;
        if (StringUtils.hasText(email)) {
            user = userService.getUserByEmailWithPassword(email);
        } else if (StringUtils.hasText(phone)) {
            user = userService.getUserByPhoneWithPassword(phone);
        } else {
            log.warn("登录失败，未提供有效的登录账号");
            throw new BusinessException("请提供邮箱或手机号");
        }

        if (user == null || !checkPassword(rawPassword, user.getPassword())) {
            log.warn("用户登录失败。Email: {}, Phone: {}", email, phone);
            throw new BusinessException("账号或密码错误");
        }

        // 生成双token
        return generateTokens(user);
    }

    @Override
    public void logout(String userId) {
        // 删除Redis中的刷新令牌
        stringRedisTemplate.delete(REDIS_KEY_PREFIX+userId);
        log.info("用户{}已登出，刷新令牌已删除", userId);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordDTO changePasswordDTO) {
        String newPassword = changePasswordDTO.getNewPassword();
        String confirmPassword = changePasswordDTO.getConfirmPassword();
        Integer verificationCode = changePasswordDTO.getVerificationCode();
        String email = changePasswordDTO.getEmail();
        if (!StringUtils.hasText(newPassword)) {
            throw new ValidationException("新密码不能为空");
        }
        String passwordRegex = "^(?=.*\\d)(?=.*[a-z])(?=.*[A-Z]).{8,}$";
        if (!newPassword.matches(passwordRegex)) {
            throw new ValidationException("密码格式错误，密码至少8位，包含大小写字母和数字");
        }
        if(!newPassword.equals(confirmPassword)){
            throw new ValidationException("两次输入的密码不一致");
        }
        // 验证验证码
        emailVerificationCodeUtils.verifyCode(email, verificationCode.toString());
        // 密码加密
        String encodedPassword = encodePassword(newPassword);
        // 更新用户密码
        userService.updatePassword(userId, encodedPassword);
    }

    @Override
    public void sendEmailVerificationCode(String email,String userIP) {
        emailVerificationCodeUtils.generateAndSendCode(email,userIP);
    }

    @Override
    public void sendPhoneVerificationCode(String phone,String userIP) {
        // TODO: 发送手机号验证码
        throw new BusinessException("手机号登录暂未支持");
    }

    /**
     * 生成双token（访问令牌和刷新令牌）
     * @param user 用户实体对象
     * @return 包含访问令牌和刷新令牌的Map对象
     */
    public Map<String, String> generateTokens(User user) {
        Long userId = user.getId();
        Integer role = user.getRole();
        //移除旧的刷新令牌
        stringRedisTemplate.delete(REDIS_KEY_PREFIX+userId);
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        String accessToken = JwtTokenUtils.generateAccessToken(claims);
        String refreshToken = JwtTokenUtils.generateRefreshToken(claims);
        // 刷新令牌存储到Redis中，键为REDIS_KEY_PREFIX + userId，值为refreshToken，过期时间为7天
        stringRedisTemplate.opsForValue().set(REDIS_KEY_PREFIX+userId, refreshToken, JwtTokenUtils.REFRESH_TOKEN_EXPIRE, TimeUnit.MILLISECONDS);
        return Map.of("accessToken",accessToken,"refreshToken",refreshToken);
    }

    /**
     * 密码加密
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
    /**
     * 密码校验
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return 密码校验结果，true表示密码匹配，false表示密码不匹配
     */
    public boolean checkPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
