package com.smartorder.edu.common.service;


import com.smartorder.edu.common.model.dto.ChangePasswordDTO;
import com.smartorder.edu.common.model.dto.LoginDTO;
import com.smartorder.edu.common.model.dto.RegisterDTO;

import java.util.Map;

/**
 * 认证服务接口，定义了登录、登出、注册和忘记密码等操作
 * @author 谢光湘
 * @since 2026/2/26
 */
public interface IAuthService {
    /**
     * 用户注册
     * @param registerDTO 注册DTO对象，包含用户名、密码、确认密码、邮箱、手机号
     */
    void register(RegisterDTO registerDTO);

    /**
     * 用户登录
     * @param loginDTO 登录DTO对象，包含用户名和密码（支持邮箱、手机号登录）
     * @return 登录成功后的用户实体对象
     */
    Map<String,String> login(LoginDTO loginDTO);

    /**
     * 用户登出
     * @param userId 用户ID
     */
    void logout(String userId);

    /**
     * 修改密码
     */
    void changePassword(Long userId, ChangePasswordDTO changePasswordDTO);

    /**
     * 发送邮箱验证码
     * @param email 邮箱
     * @param userIP 用户IP地址，用于防止恶意请求
     */
    void sendEmailVerificationCode(String email,String userIP);

    /**
     * 发送手机验证码
     * @param phone 手机号
     * @param userIP 用户IP地址，用于防止恶意请求
     */
    void sendPhoneVerificationCode(String phone,String userIP);

    /**
     * GitHub OAuth 登录
     * @param githubLoginDTO 包含 code 和 state 的 DTO
     * @return 包含 accessToken 和 refreshToken 的 Map
     */
    Map<String, String> githubLogin(com.smartorder.edu.common.model.dto.GithubLoginDTO githubLoginDTO);
}
