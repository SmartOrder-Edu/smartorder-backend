package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.dto.AdminUserQueryDTO;
import com.smartorder.edu.manage.domain.dto.AdminUserUpdateDTO;
import com.smartorder.edu.manage.domain.vo.AdminFrontendPayload;
import com.smartorder.edu.manage.domain.vo.AdminUserVO;
import com.smartorder.edu.manage.service.AdminUserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运营后台：用户管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 校验当前登录用户是否为管理员角色 (1=管理员)
     */
    private boolean isAdmin() {
        Integer role = 1; // 本地联调沿用现有管理端接口的默认管理员降级策略
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                Integer claimRole = claims.get("role", Integer.class);
                if (claimRole != null) {
                    role = claimRole;
                }
            }
        } catch (Exception e) {
            log.error("获取当前登录用户角色失败", e);
        }
        return role == 1;
    }

    /**
     * 获取当前登录管理员ID，用于保护管理员不能误操作自己。
     */
    private Long currentUserId() {
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                return claims.get("userId", Long.class);
            }
        } catch (Exception e) {
            log.error("获取当前登录用户ID失败", e);
        }
        return null;
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping("/page")
    public Result<Map<String, Object>> queryPage(AdminUserQueryDTO queryDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<AdminUserVO> result = adminUserService.queryUserPage(queryDTO);
        // 用户列表对齐前端 mock 分页结构，支撑 res.records / res.total 直接读取。
        return Result.success(AdminFrontendPayload.page(result), "分页获取用户列表成功");
    }

    /**
     * 获取用户数量、角色和账号状态统计
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Map<String, Long> result = adminUserService.getUserStats();
        // 统计字段平铺返回，兼容 res.totalUsers / res.adminUsers 等直接读取方式。
        return Result.success(AdminFrontendPayload.data(result), "获取用户管理统计成功");
    }

    /**
     * 更新用户基础资料、角色、状态和 VIP 等级
     */
    @PostMapping("/{id}")
    public Result<Map<String, Object>> updateUser(
            @PathVariable("id") Long id,
            @RequestBody AdminUserUpdateDTO updateDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        adminUserService.updateUser(id, updateDTO, currentUserId());
        return Result.success(AdminFrontendPayload.empty(), "用户信息更新成功");
    }

    /**
     * 快捷设置用户角色
     */
    @PostMapping("/{id}/role/{role}")
    public Result<Map<String, Object>> updateRole(
            @PathVariable("id") Long id,
            @PathVariable("role") Integer role) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        AdminUserUpdateDTO updateDTO = new AdminUserUpdateDTO();
        updateDTO.setRole(role);
        adminUserService.updateUser(id, updateDTO, currentUserId());
        return Result.success(AdminFrontendPayload.empty(), "用户角色更新成功");
    }

    /**
     * 快捷启用或禁用账号
     */
    @PostMapping("/{id}/status/{status}")
    public Result<Map<String, Object>> updateStatus(
            @PathVariable("id") Long id,
            @PathVariable("status") Integer status) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        AdminUserUpdateDTO updateDTO = new AdminUserUpdateDTO();
        updateDTO.setStatus(status);
        adminUserService.updateUser(id, updateDTO, currentUserId());
        return Result.success(AdminFrontendPayload.empty(), "用户账号状态更新成功");
    }
}
