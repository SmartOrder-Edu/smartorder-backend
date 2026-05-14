package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 获取学生画像工具——迁移自 ProfileTool
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetStudentProfileTool implements ToolHandler {

    private final com.smartorder.edu.user.service.IStudentProfileService profileService;

    @Override
    public String name() {
        return "getStudentProfile";
    }

    @Override
    public String description() {
        return "获取学生画像，包括学习偏好、基础水平、薄弱知识点、动态标签";
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
        Long userId = req.getUserId() != null ? req.getUserId() : context.getUserId();

        log.info("[GetStudentProfile] userId={}", userId);

        if (context.getUserId() != null && !context.getUserId().equals(userId)) {
            throw new IllegalArgumentException("不能查询其他用户的学生画像");
        }

        return profileService.getByUserId(userId);
    }

    @Data
    public static class GetStudentProfileRequest {
        private Long userId;
    }
}
