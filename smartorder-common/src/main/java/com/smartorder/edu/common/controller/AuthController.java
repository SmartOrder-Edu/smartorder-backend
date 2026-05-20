package com.smartorder.edu.common.controller;


import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.dto.ChangePasswordDTO;
import com.smartorder.edu.common.model.dto.LoginDTO;
import com.smartorder.edu.common.model.dto.RegisterDTO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.model.vo.UserVO;
import com.smartorder.edu.common.service.IAuthService;
import com.smartorder.edu.common.service.IUserService;
import com.smartorder.edu.common.util.JwtTokenUtils;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器，处理登录、登出、注册和忘记密码等请求
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
@Tag(name="认证接口", description="处理用户登录、注册、登出和密码修改等认证相关的请求")
public class AuthController {

    private final IAuthService authService;
    private final IUserService userService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 用户注册
     * @param registerDTO 注册数据传输对象
     * @return 注册结果
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "用户注册接口")
    public Result<String> register(@RequestBody RegisterDTO registerDTO) {
        authService.register(registerDTO);
        return Result.success(null, "注册成功");
    }

    /**
     * 登录
     * @param loginDTO 登录数据传输对象
     * @param request HttpServletRequest对象
     * @return 登录成功后返回访问令牌（data中）和刷新令牌（Set-Cookie中）
     */
    @PostMapping("/login")
    @Operation(summary="用户登录", description = "登录成功后返回访问令牌（data中）和刷新令牌（Set-Cookie中）")
    public ResponseEntity<Result<String>> login(@RequestBody LoginDTO loginDTO, HttpServletRequest request, HttpServletResponse response) {
        log.info("IP: {} 正在尝试登录, Email: {}, Phone: {}", request.getRemoteAddr(), loginDTO.getEmail(), loginDTO.getPhone());
        loginDTO.setIp(request.getRemoteAddr());

        Map<String,String> tokens = authService.login(loginDTO);

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken",tokens.get("refreshToken"))
                .httpOnly(true)     //防止XSS攻击
                .path("/")
                .maxAge(JwtTokenUtils.REFRESH_TOKEN_EXPIRE / 1000)
                .build();
        response.addHeader("Set-Cookie", refreshCookie.toString());

        return ResponseEntity.ok(Result.success(tokens.get("accessToken"), "登录成功"));
    }


    /**
     * 发送邮箱验证码
     * @param email 邮箱
     * @return 发送成功的消息
     */
    @GetMapping("/sendVerificationCode/email")
    @Operation(summary = "发送邮箱验证码", description = "发送邮箱验证码接口")
    public Result<String> sendVerificationCode(@RequestParam String email, HttpServletRequest request) {
        log.info("IP: {} 正在尝试发送邮箱验证码, Email: {}", request.getRemoteAddr(), email);
        authService.sendEmailVerificationCode(email, request.getRemoteAddr());
        return Result.success(null, "验证码已发送");
    }


    /**
     * 用户登出
     * @return 登出结果
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "用户登出接口")
    public Result<String> logout() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        authService.logout(userId.toString());
        return Result.success(null, "已成功退出登录");
    }



    @PostMapping("/forget")
    public String forget() {
        return "forget";
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public Result<String> changePassword(@RequestBody ChangePasswordDTO changePasswordDTO) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        authService.changePassword(userId, changePasswordDTO);
        return Result.success(null, "密码修改成功");
    }

    /**
     * 刷新访问令牌
     * @param request HttpServletRequest对象
     * @param response HttpServletResponse对象
     * @return 刷新成功后返回新的访问令牌（data中）和刷新令牌（Set-Cookie中）
     */
    @PostMapping("/refreshToken")
    public ResponseEntity<Result<String>> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        log.info("IP: {} 正在尝试刷新访问令牌", request.getRemoteAddr());
        //从请求的HttpOnlyCookie中获取刷新令牌
        Cookie refreshCookie = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookie == null){
            log.warn("刷新令牌为空");
            return ResponseEntity.status(401).body(Result.fail(ResultCode.UNAUTHORIZED,"登录已过期，请重新登录"));
        }
        String refreshToken = refreshCookie.getValue();
        try {
            Claims claims = JwtTokenUtils.getClaimsFromToken(refreshToken, JwtTokenUtils.REFRESH_TOKEN_SECRET);
            log.info("刷新令牌，userId: {}", claims.get("userId", Long.class));
            Long userId = claims.get("userId", Long.class);

            // 校验 Redis 中的 refreshToken
            String redisKey = "refreshToken:" + userId;
            String savedRefreshToken = stringRedisTemplate.opsForValue().get(redisKey);

            if (savedRefreshToken == null || !savedRefreshToken.equals(refreshToken)) {
                log.warn("刷新令牌无效或已过期，userId: {}", userId);
                return ResponseEntity.status(401).body(Result.fail(ResultCode.UNAUTHORIZED, "登录状态失效，请重新登录"));
            }

            UserVO userVO = userService.getUserInfo(userId);
            Map<String,Object> map = new HashMap<>();
            map.put("userId",userId);
            map.put("role", userVO.getRole());
            String newAccessToken = JwtTokenUtils.generateAccessToken(map);
            String newRefreshToken = JwtTokenUtils.generateRefreshToken(map);

            // 将新的 refreshToken 存入 Redis
            stringRedisTemplate.opsForValue().set(redisKey, newRefreshToken, JwtTokenUtils.REFRESH_TOKEN_EXPIRE, java.util.concurrent.TimeUnit.MILLISECONDS);

            //设置新的刷新令牌到HttpOnlyCookie中
            ResponseCookie newRefreshCookie = ResponseCookie.from("refreshToken",newRefreshToken)
                    .httpOnly(true)
                    .path("/")
                    .maxAge(JwtTokenUtils.REFRESH_TOKEN_EXPIRE / 1000)
                    .build();
            response.addHeader("Set-Cookie", newRefreshCookie.toString());
            return ResponseEntity.ok(Result.success(newAccessToken, "令牌刷新成功"));
        } catch (Exception e) {
            log.error("刷新令牌失败: {}", e.getMessage());
            return ResponseEntity.status(401).body(Result.fail(ResultCode.UNAUTHORIZED,"登录已过期，请重新登录"));
        }
    }
}