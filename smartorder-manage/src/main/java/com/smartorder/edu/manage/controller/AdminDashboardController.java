package com.smartorder.edu.manage.controller;

import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.vo.AdminDashboardChartsVO;
import com.smartorder.edu.manage.domain.vo.AdminDashboardStatsVO;
import com.smartorder.edu.manage.service.AdminManageService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营管理端：系统大盘看板
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardController {

    private final AdminManageService adminManageService;

    /**
     * 校验当前登录用户是否为管理员角色 (1=管理员)
     */
    private boolean isAdmin() {
        Integer role = 1; // 默认测试降级为管理员
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                Integer claimRole = claims.get("role", Integer.class);
                if (claimRole != null) {
                    role = claimRole;
                }
            }
        } catch (Exception e) {
            log.error("大盘获取当前登录用户信息失败", e);
        }
        return role == 1;
    }

    /**
     * 获取后台大盘卡片数据汇总
     */
    @GetMapping("/stats")
    public Result<AdminDashboardStatsVO> getStats() {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        AdminDashboardStatsVO stats = adminManageService.getDashboardStats();
        return Result.success(stats, "获取系统大盘卡片数据成功");
    }

    /**
     * 获取后台大盘 ECharts 图表数据
     */
    @GetMapping("/charts")
    public Result<AdminDashboardChartsVO> getCharts() {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        AdminDashboardChartsVO charts = adminManageService.getDashboardCharts();
        return Result.success(charts, "获取系统大盘图表数据成功");
    }
}
