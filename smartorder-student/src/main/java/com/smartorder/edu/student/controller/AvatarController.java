package com.smartorder.edu.student.controller;


import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.student.util.XfAvatarAuthUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static com.smartorder.edu.common.constant.ResultCode.BUSINESS_ERROR;


/**
 * 虚拟人 3D 教师控制层
 */
@RestController
@RequestMapping("/teacher/avatar")
public class AvatarController {

    @Value("${xfyun.avatar.app-id}")
    private String appId;

    @Value("${xfyun.avatar.api-key}")
    private String apiKey;

    @Value("${xfyun.avatar.api-secret}")
    private String apiSecret;

    @Value("${xfyun.avatar.host-url}")
    private String hostUrl;

    @Value("${xfyun.avatar.scene-id}")
    private String sceneId;

    /** 控制前端数字人入口，未配置时默认关闭。 */
    @Value("${smartorder.avatar.enabled:false}")
    private boolean enabled;

    /** 只向前端公开展示开关，不返回服务商鉴权配置。 */
    @GetMapping("/config")
    public Result<Map<String, Boolean>> getAvatarConfig() {
        return Result.success(Map.of("enabled", enabled));
    }

    /**
     * 获取带有签名的 WebSocket 连接地址和必要的初始参数
     * 前端拿到这个 wssUrl 就可以直接 new WebSocket(wssUrl) 了
     */
    @GetMapping("/auth-info")
    public Result<Map<String, String>> getAvatarAuthInfo() {
        // 关闭数字人后拒绝生成签名，避免隐藏入口仍可触发云端连接。
        if (!enabled) {
            return Result.fail(BUSINESS_ERROR, "数字人功能未开启");
        }
        try {
            // 核心：调用刚才写的工具类生成带签名的安全 URL
            String signedWssUrl = XfAvatarAuthUtil.assembleRequestUrl(hostUrl, apiKey, apiSecret);

            // 将前端发起 start 协议必须的参数一并下发，避免前端硬编码
            Map<String, String> authData = new HashMap<>();
            authData.put("wssUrl", signedWssUrl);
            authData.put("appId", appId);
            authData.put("sceneId", sceneId);

            return Result.success(authData);
        } catch (Exception e) {
            return Result.fail(BUSINESS_ERROR, "获取虚拟人鉴权信息失败：" + e.getMessage());
        }
    }
}
