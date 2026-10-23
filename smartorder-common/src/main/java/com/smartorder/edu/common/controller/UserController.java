package com.smartorder.edu.common.controller;


import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.dto.BindAuthDTO;
import com.smartorder.edu.common.model.dto.ChangeEmailDTO;
import com.smartorder.edu.common.model.dto.UserDTO;
import com.smartorder.edu.common.model.vo.AuthIdentityVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.model.vo.UserVO;
import com.smartorder.edu.common.service.IUserAuthIdentityService;
import com.smartorder.edu.common.service.impl.UserServiceImpl;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户控制器(用户修改用户信息、获取用户信息、用户注销)
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/users")
@Tag(name = "用户管理", description = "用户修改用户信息、获取用户信息、用户注销接口")
public class UserController {

    private final UserServiceImpl userService;
    private final IUserAuthIdentityService userAuthIdentityService;

    /**
     * 获取当前用户的第三方绑定列表
     * @return Result 包含绑定列表
     */
    @GetMapping("/bindings")
    @Operation(summary = "获取第三方绑定列表", description = "获取当前用户所有已绑定的第三方平台身份")
    public Result<List<AuthIdentityVO>> bindings() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        List<AuthIdentityVO> identities = userAuthIdentityService.listIdentities(userId);
        return Result.success(identities);
    }

    /**
     * 绑定第三方账号
     * @param bindAuthDTO 绑定请求（含 providerCode、code、state）
     * @return Result 结果
     */
    @PostMapping("/bindings")
    @Operation(summary = "绑定第三方账号", description = "通过 OAuth 授权码绑定第三方平台账号")
    public Result<String> bindAuth(@RequestBody BindAuthDTO bindAuthDTO) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);

        // TODO: 根据 providerCode 调用对应的 OAuth 服务获取第三方用户信息
        // 目前绑定逻辑在 AuthServiceImpl 的 OAuth 登录流程中处理
        // 独立的 bind 功能需要用户在已登录状态下走 OAuth 授权流程
        log.info("用户 {} 请求绑定 {} 平台", userId, bindAuthDTO.getProviderCode());
        return Result.success("绑定请求已提交");
    }

    /**
     * 解绑第三方账号
     * @param providerCode 第三方平台标识（如 github/gitee）
     * @return Result 结果
     */
    @DeleteMapping("/bindings/{providerCode}")
    @Operation(summary = "解绑第三方账号", description = "解绑指定平台的第三方账号绑定")
    public Result<String> unbindAuth(@PathVariable String providerCode) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);

        // 检查用户是否有其他登录方式，防止唯一登录方式解绑后无法登录
        com.smartorder.edu.common.model.entity.User user = userService.getUserById(userId);
        if (user == null) {
            return Result.fail(ResultCode.BUSINESS_ERROR, "用户不存在");
        }
        boolean hasPassword = user.getPassword() != null && !user.getPassword().isBlank();
        if (!userAuthIdentityService.hasOtherLoginMethod(userId, providerCode, hasPassword)) {
            return Result.fail(ResultCode.BUSINESS_ERROR, "这是您唯一的登录方式，请先设置密码或绑定其他平台后再解绑");
        }

        userAuthIdentityService.unbindIdentity(userId, providerCode);
        log.info("用户 {} 成功解绑 {} 平台", userId, providerCode);
        return Result.success("解绑成功");
    }


    /**
     * 根据用户ID获取用户信息
     * @return Result<UserVO> 结果对象，包含用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户信息")
    public Result<UserVO> info() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        UserVO userInfo = userService.getUserInfo(userId);
        log.info("用户信息获取成功: {}", userInfo);
        return Result.success(userInfo);
    }

    /**
     * 编辑用户信息
     * @param userDTO 用户数据传输对象
     * @return Result<String> 结果对象，包含更新成功的消息
     */
    @PostMapping("/profile")
    @Operation(summary = "编辑用户信息", description = "编辑用户昵称、头像等信息")
    public Result<String> profile(@RequestBody UserDTO userDTO) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        userDTO.setId(userId);
        userService.updateUserById(userDTO);
        log.info("用户信息更新成功");
        return Result.success("用户信息更新成功");
    }


    /**
     * 修改邮箱（需验证码）
     * @param changeEmailDTO 包含新邮箱和验证码
     * @return Result 结果
     */
    @PostMapping("/email")
    @Operation(summary = "修改邮箱", description = "通过邮箱验证码修改绑定邮箱")
    public Result<String> changeEmail(@RequestBody ChangeEmailDTO changeEmailDTO) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        userService.changeEmail(userId, changeEmailDTO.getNewEmail(), changeEmailDTO.getVerificationCode());
        log.info("用户 {} 邮箱修改成功", userId);
        return Result.success(null, "邮箱修改成功");
    }

    @PutMapping("/avatar")
    public Result<String> updateAvatar(@RequestParam("file") MultipartFile avatar){
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        String url = userService.uploadAvatar(userId, avatar);
        log.info("用户{}头像更新成功: {}", userId, url);
        return Result.success(url, "用户头像更新成功");
    }

    /**
     * 注销用户(发起注销 → 手机验证码 → 执行注销)
     * @param verificationCode 验证码
     * @return Result<String> 结果对象，包含注销成功的消息
     */
    @PostMapping("/cancel/")
    public Result<String> cancel(@RequestParam String verificationCode) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        log.info("正在注销用户{}", userId);
        userService.cancelUserAccount(userId, verificationCode);
        log.info("用户{}注销成功", userId);
        return Result.success("用户注销成功");
    }
}
