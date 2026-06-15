package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolResult;
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
    public String userFriendlyErrorMessage() {
        return "暂时无法获取你的学习档案信息，建议直接告诉我你的学习目标和当前水平";
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GetStudentProfileRequest req = (GetStudentProfileRequest) request;
        // 禁止模型传入 userId，统一使用上下文中的 userId
        Long userId = context.getUserId();

        log.info("[GetStudentProfile] userId={}", userId);

        try {
            Object profile = profileService.getByUserId(userId);
            if (profile == null) {
                // 新用户尚未建立画像，返回友好提示让 LLM 主动询问
                return ToolResult.success(
                    "该学生尚未完成画像建档，暂无学习偏好、知识基础等信息。" +
                    "建议通过对话方式了解学生的学习目标、当前水平、可用时间等关键信息，以辅助后续规划。");
            }
            return profile;
        } catch (Exception e) {
            log.error("[GetStudentProfile] 查询学生画像异常: userId={}", userId, e);
            return ToolResult.error(
                "暂时无法获取学生的学习档案信息，建议通过对话直接了解学生的学习目标和当前水平。",
                e.getMessage());
        }
    }

    @Data
    public static class GetStudentProfileRequest {
        // 不再接受 userId 参数，统一使用 ToolInvokeContext.userId
        // 保留空请求体即可
    }
}
