package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 获取学生画像工具 — 使用 JSON 维度画像
 *
 * @author 谢光湘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetStudentProfileTool implements ToolHandler {

    private final IStudentProfileService profileService;

    @Override
    public String name() {
        return "getStudentProfile";
    }

    @Override
    public String description() {
        return "获取学生画像，包括基础信息、学习目标、知识基础、薄弱点、偏好等 JSON 维度数据";
    }

    @Override
    public Class<?> requestType() {
        return GetStudentProfileRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROFILE;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GetStudentProfileRequest req = (GetStudentProfileRequest) request;
        // 禁止模型传入 userId，统一使用上下文中的 userId
        Long userId = context.getUserId();

        log.info("[GetStudentProfile] userId={}", userId);

        return profileService.getByUserId(userId);
    }

    @Data
    public static class GetStudentProfileRequest {
        // 不再接受 userId 参数，统一使用 ToolInvokeContext.userId
        // 保留空请求体即可
    }
}
