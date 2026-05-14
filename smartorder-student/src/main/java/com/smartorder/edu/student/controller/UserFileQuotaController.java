package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.entity.UserFileQuota;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/quota")
@Tag(name = "用户文件配额管理", description = "用户存储空间配额查询与更新接口")
public class UserFileQuotaController {

    private final IUserFileQuotaService userFileQuotaService;

    @GetMapping
    @Operation(summary = "获取配额信息", description = "获取当前用户的存储配额及使用情况")
    public Result<UserFileQuota> getQuota() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        UserFileQuota quota = userFileQuotaService.getQuota(userId);
        return Result.success(quota);
    }

    @PostMapping("/init")
    @Operation(summary = "初始化配额", description = "为新用户初始化存储配额")
    public Result<UserFileQuota> initQuota() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        UserFileQuota quota = userFileQuotaService.initQuota(userId);
        return Result.success(quota);
    }
}
