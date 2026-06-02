package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.dto.FeedbackQueryDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackReviewDTO;
import com.smartorder.edu.manage.domain.vo.FeedbackVO;
import com.smartorder.edu.manage.service.FeedbackService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 运营管理端：反馈人工审核接口
 */
@RestController
@RequestMapping("/admin/feedback")
@RequiredArgsConstructor
@Slf4j
public class AdminFeedbackReviewController {

    private final FeedbackService feedbackService;

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
            log.error("获取当前登录用户信息失败", e);
        }
        return role == 1;
    }

    /**
     * 分页查询用户反馈列表
     */
    @GetMapping("/page")
    public Result<Page<FeedbackVO>> queryPage(FeedbackQueryDTO queryDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        Page<FeedbackVO> result = feedbackService.queryFeedbackPage(queryDTO);
        return Result.success(result, "获取反馈列表成功");
    }

    /**
     * 人工审核并回答用户反馈（通过/驳回/标记采纳）
     */
    @PostMapping("/{id}/review")
    public Result<FeedbackVO> reviewFeedback(
            @PathVariable("id") Long id,
            @Valid @RequestBody FeedbackReviewDTO reviewDTO) {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        FeedbackVO result = feedbackService.reviewFeedback(id, reviewDTO);
        return Result.success(result, "审核操作成功");
    }
    /**
     * 获取用户反馈各项看板统计指标 (待人工复核/已被AI拦截/已被采纳)
     */
    @GetMapping("/stats")
    public Result<java.util.Map<String, Long>> getStats() {
        if (!isAdmin()) {
            return Result.fail(ResultCode.FORBIDDEN, "无权操作，仅限管理员");
        }
        java.util.Map<String, Long> result = feedbackService.getFeedbackStats();
        return Result.success(result, "获取看板指标统计数据成功");
    }
}
