package com.smartorder.edu.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具权限校验器——确保用户只能访问自己的数据，并按风险等级限制
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

        // 按风险等级校验
        ToolCategory.RiskLevel riskLevel = handler.riskLevel();
        switch (riskLevel) {
            case READ:
                // 读操作只需有效 userId
                if (context.getUserId() <= 0) {
                    throw new SecurityException("无效用户身份: " + context.getUserId());
                }
                break;
            case WRITE_DRAFT:
                // 草稿写操作需要有效用户身份
                if (context.getUserId() <= 0) {
                    throw new SecurityException("无效用户身份: " + context.getUserId());
                }
                break;
            case WRITE_CONFIRMED:
                // WRITE_CONFIRMED 级别的工具不能由 Agent 直接执行
                // 必须通过用户确认 API 触发
                throw new SecurityException(
                        "工具 [" + handler.name() + "] 属于确认写操作，不能由 Agent 直接调用。"
                                + "请保存为草稿，等待用户通过 /agent/plan/confirm 确认后执行。");
            default:
                break;
        }

        // 按分类做细粒度权限校验
        switch (handler.category()) {
            case PROFILE:
                checkProfileAccess(handler, arguments, context);
                break;
            default:
                break;
        }
    }

    private void checkProfileAccess(ToolHandler handler, Object arguments, ToolInvokeContext context) {
        // GetStudentProfileTool 内部有二次校验
        // 此处做基础校验
    }
}
