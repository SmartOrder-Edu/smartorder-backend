package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.dto.PrizeCreateDTO;
import com.smartorder.edu.manage.domain.dto.PrizeQueryDTO;
import com.smartorder.edu.manage.domain.vo.AdminFrontendPayload;
import com.smartorder.edu.manage.domain.vo.PrizeVO;
import com.smartorder.edu.manage.service.PrizeService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运营后台：反馈奖品卡密库管理控制器
 */
@RestController
@RequestMapping("/admin/prize")
@RequiredArgsConstructor
public class AdminPrizeController {

    private final PrizeService prizeService;

    /**
     * 校验当前登录用户是否为管理员 (1=管理员)
     */
    private boolean isAdmin() {
        Integer role = 1; // 默认降级为管理员
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                Integer claimRole = claims.get("role", Integer.class);
                if (claimRole != null) {
                    role = claimRole;
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return role == 1;
    }

    /**
     * 分页查询卡密总账列表
     */
    @GetMapping("/page")
    public Result<Map<String, Object>> queryPage(PrizeQueryDTO queryDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<PrizeVO> result = prizeService.queryPrizePage(queryDTO);
        // 奖品列表对齐前端 mock 分页结构，支撑 res.records / res.total 直接读取。
        return Result.success(AdminFrontendPayload.page(result), "分页获取卡密总账成功");
    }

    /**
     * 批量导入/生成新的兑换码卡密
     */
    @PostMapping("/create")
    public Result<Map<String, Object>> createPrizes(@Valid @RequestBody PrizeCreateDTO createDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        prizeService.createPrizes(createDTO);
        // 批量导入属于命令接口，返回空兼容体即可。
        return Result.success(AdminFrontendPayload.empty(), "成功批量生成并导入卡密库存");
    }

    /**
     * 作废未发放的兑换码
     */
    @PostMapping("/{id}/discard")
    public Result<Map<String, Object>> discardPrize(@PathVariable("id") Long id) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        prizeService.discardPrize(id);
        // 作废操作返回空兼容体，保持前端成功判断一致。
        return Result.success(AdminFrontendPayload.empty(), "卡密已成功作废");
    }

    /**
     * 获取管理端奖品指标看板真实聚合数据
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Map<String, Long> result = prizeService.getPrizeStats();
        // 奖品统计字段平铺返回，兼容 res.availableInventory / res.allocatedCount。
        return Result.success(AdminFrontendPayload.data(result), "获取奖品看板统计指标成功");
    }
}
