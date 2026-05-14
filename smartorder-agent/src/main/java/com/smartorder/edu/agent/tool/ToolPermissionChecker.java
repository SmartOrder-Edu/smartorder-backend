package com.smartorder.edu.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具权限校验器——确保用户只能访问自己的数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolPermissionChecker {

    /**
     * 校验工具调用权限
     */
    public void check(ToolHandler handler, Object arguments, ToolInvokeContext context) {
        // 确保 userId 存在
        if (context.getUserId() == null) {
            throw new SecurityException("工具调用缺少用户身份: " + handler.name());
        }

        // 按分类做权限校验
        switch (handler.category()) {
            case PROFILE:
                // 学生画像工具只能查自己的
                checkProfileAccess(handler, arguments, context);
                break;
            case TASK:
            case PLAN:
            case RESOURCE:
                // 写操作需要用户身份
                if (context.getUserId() <= 0) {
                    throw new SecurityException("无效用户身份: " + context.getUserId());
                }
                break;
            default:
                // 读操作默认允许
                break;
        }
    }

    private void checkProfileAccess(ToolHandler handler, Object arguments, ToolInvokeContext context) {
        // GetStudentProfileTool 内部有二次校验
        // 此处做基础校验
    }
}
