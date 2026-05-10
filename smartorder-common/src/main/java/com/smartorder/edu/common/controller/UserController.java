package com.smartorder.edu.common.controller;


import com.smartorder.edu.common.domain.dto.UserDTO;
import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.domain.vo.UserVO;
import com.smartorder.edu.common.service.impl.UserServiceImpl;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
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
