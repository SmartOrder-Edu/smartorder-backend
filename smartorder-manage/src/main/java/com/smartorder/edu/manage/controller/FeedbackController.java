package com.smartorder.edu.manage.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.manage.domain.dto.FeedbackQueryDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackSubmitDTO;
import com.smartorder.edu.manage.domain.vo.FeedbackVO;
import com.smartorder.edu.manage.service.FeedbackService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端反馈前端接口
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * 获取当前登录用户的 userId 和 role (0=学生, 1=管理员, 2=老师)
     */
    private Object[] getCurrentUser() {
        Long userId = 1L;
        Integer role = 0; // 默认是学生角色
        try {
            Claims claims = ThreadLocalUtils.get();
            if (claims != null) {
                Long claimUserId = claims.get("userId", Long.class);
                if (claimUserId != null) {
                    userId = claimUserId;
                }
                Integer claimRole = claims.get("role", Integer.class);
                if (claimRole != null) {
                    role = claimRole;
                }
            }
        } catch (Exception e) {
            log.error("获取当前登录用户信息失败", e);
            // 忽略，测试环境降级为 mock
        }
        return new Object[]{userId, role};
    }

    /**
     * 用户提交反馈
     */
    @PostMapping("/submit")
    public Result<FeedbackVO> submit(@Valid @RequestBody FeedbackSubmitDTO submitDTO) {
        Object[] user = getCurrentUser();
        Long userId = (Long) user[0];
        FeedbackVO result = feedbackService.submitFeedback(submitDTO, userId);
        return Result.success(result, "提交反馈成功");
    }

    /**
     * 查询我的历史反馈记录（学生）
     */
    @GetMapping("/my-page")
    public Result<Page<FeedbackVO>> queryMyPage(FeedbackQueryDTO queryDTO) {
        Object[] user = getCurrentUser();
        Long userId = (Long) user[0];
        Page<FeedbackVO> result = feedbackService.queryMyFeedbackPage(queryDTO, userId);
        return Result.success(result, "获取历史反馈成功");
    }

    /**
     * 用户修改未审核的反馈内容
     */
    @PutMapping("/{id}")
    public Result<FeedbackVO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody FeedbackSubmitDTO submitDTO) {
        Object[] user = getCurrentUser();
        Long userId = (Long) user[0];
        Integer role = (Integer) user[1];
        FeedbackVO result = feedbackService.updateFeedback(id, submitDTO, userId, role);
        return Result.success(result, "修改反馈成功，已重新进行智能初审");
    }

    /**
     * 用户或管理员删除反馈（物理删除/逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable("id") Long id) {
        Object[] user = getCurrentUser();
        Long userId = (Long) user[0];
        Integer role = (Integer) user[1];
        feedbackService.deleteFeedback(id, userId, role);
        return Result.success("反馈删除成功");
    }

}
