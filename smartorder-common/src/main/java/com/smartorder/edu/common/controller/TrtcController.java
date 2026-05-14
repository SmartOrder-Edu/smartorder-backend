package com.smartorder.edu.common.controller;



import com.smartorder.edu.common.service.TrtcService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/trtc")
public class TrtcController {

    @Autowired
    private TrtcService trtcService;

    /**
     * 前端获取 TRTC 进房票据
     */
    @GetMapping("/getUserSig")
    public Map<String, Object> getUserSig(HttpServletRequest request) {
        // 1. 从你的拦截器或 JWT Token 中获取当前登录用户的 ID
        // 这里简化演示，假设你已经将 userId 放入了 Request Attribute
        Long currentUserId = (Long) request.getAttribute("userId");
        String userIdStr = String.valueOf(currentUserId);

        // 2. 调用 Service 生成签名
        String userSig = trtcService.generateUserSig(userIdStr);

        // 3. 将必要参数返回给前端
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userIdStr);
        data.put("userSig", userSig);
        // 通常前端还需要知道 AppId 才能初始化 SDK，一并下发比较规范
        data.put("sdkAppId", trtcService.getSdkAppId());

        // return Result.success(data);
        return data;
    }
}
