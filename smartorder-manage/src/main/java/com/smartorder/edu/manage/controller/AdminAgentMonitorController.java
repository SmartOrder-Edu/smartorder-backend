package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.vo.AdminFrontendPayload;
import com.smartorder.edu.manage.domain.vo.AgentResourceLogVO;
import com.smartorder.edu.manage.domain.vo.AgentToolExecutionVO;
import com.smartorder.edu.manage.service.AdminManageService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营管理端：智能体与工具监控接口
 */
@RestController
@RequestMapping("/admin/agent")
@RequiredArgsConstructor
@Slf4j
public class AdminAgentMonitorController {

    private final AdminManageService adminManageService;

    private boolean isAdmin() {
        Integer role = 1;
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                Integer claimRole = claims.get("role", Integer.class);
                if (claimRole != null) {
                    role = claimRole;
                }
            }
        } catch (Exception e) {
            log.error("智能体监控获取当前登录用户信息失败", e);
        }
        return role == 1;
    }

    /**
     * 分页查询智能体工具调用审计日志
     */
    @GetMapping("/tool/list")
    public Result<Map<String, Object>> getToolExecutionList(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestParam(value = "status", required = false) String status) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<AgentToolExecutionVO> result = adminManageService.queryToolExecutionPage(page, size, toolName, status);
        // 管理端表格分页对齐前端 mock 的 records/total/page/size 字段。
        return Result.success(AdminFrontendPayload.page(result), "获取智能体工具审计列表成功");
    }

    /**
     * 分页查询资源生成进度日志
     */
    @GetMapping("/resource/list")
    public Result<Map<String, Object>> getResourceLogList(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "resourceType", required = false) String resourceType,
            @RequestParam(value = "status", required = false) String status) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<AgentResourceLogVO> result = adminManageService.queryResourceLogPage(page, size, resourceType, status);
        // 资源生成日志保持管理端分页兼容结构，方便前端直接替换 mock。
        return Result.success(AdminFrontendPayload.page(result), "获取资源生成日志成功");
    }
}
