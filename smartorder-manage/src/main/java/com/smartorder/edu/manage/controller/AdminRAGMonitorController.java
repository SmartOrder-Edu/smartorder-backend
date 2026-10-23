package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.vo.AdminFrontendPayload;
import com.smartorder.edu.manage.domain.vo.KnowledgeFileJobVO;
import com.smartorder.edu.manage.service.AdminManageService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运营管理端：知识库与 RAG 解析任务队列监控
 */
@RestController
@RequestMapping("/admin/rag")
@RequiredArgsConstructor
@Slf4j
public class AdminRAGMonitorController {

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
            log.error("RAG 监控获取当前登录用户信息失败", e);
        }
        return role == 1;
    }

    /**
     * 分页查询文件解析与向量化任务队列
     */
    @GetMapping("/jobs")
    public Result<Map<String, Object>> getRAGJobList(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "status", required = false) String status) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<KnowledgeFileJobVO> result = adminManageService.queryRAGJobPage(page, size, status);
        // RAG 队列分页对齐前端 mock 的 records/total/page/size 字段。
        return Result.success(AdminFrontendPayload.page(result), "获取 RAG 解析异步任务列表成功");
    }

    /**
     * 重新触发异步解析文件任务
     */
    @PostMapping("/jobs/retry/{jobId}")
    public Result<Map<String, Object>> retryRAGJob(@PathVariable("jobId") Long jobId) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        boolean success = adminManageService.retryRAGJob(jobId);
        if (success) {
            // 命令类接口返回空兼容体，支撑旧页面里的 res.data.code 成功判断。
            return Result.success(AdminFrontendPayload.empty(), "已成功重新触发解析任务");
        } else {
            return Result.fail(ResultCode.NOT_FOUND, "未找到指定的任务，重置失败");
        }
    }
}
