package com.smartorder.edu.manage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartorder.edu.manage.domain.dto.FeedbackQueryDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackReviewDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackSubmitDTO;
import com.smartorder.edu.manage.domain.po.Feedback;
import com.smartorder.edu.manage.domain.vo.FeedbackVO;

/**
 * 反馈业务服务接口
 */
public interface FeedbackService extends IService<Feedback> {

    /**
     * 提交反馈（包含AI审核逻辑）
     * @param submitDTO 提交的内容
     * @param userId 当前用户ID
     * @return 提交成功后的反馈对象展示
     */
    FeedbackVO submitFeedback(FeedbackSubmitDTO submitDTO, Long userId);

    /**
     * 分页查询反馈（管理端）
     * @param queryDTO 查询条件
     * @return 反馈分页VO列表
     */
    Page<FeedbackVO> queryFeedbackPage(FeedbackQueryDTO queryDTO);

    /**
     * 审核反馈（管理端）
     * @param id 反馈记录ID
     * @param reviewDTO 审核参数
     * @return 审核完成后的反馈VO
     */
    FeedbackVO reviewFeedback(Long id, FeedbackReviewDTO reviewDTO);

    /**
     * 删除反馈
     * @param id 反馈ID
     * @param userId 操作用户ID
     * @param role 操作用户角色：0=学生，1=管理员，2=老师
     */
    void deleteFeedback(Long id, Long userId, Integer role);

    /**
     * 修改反馈（如果是学生/老师且处于未审核状态，重新触发AI初审）
     * @param id 反馈ID
     * @param submitDTO 修改的内容
     * @param userId 操作用户ID
     * @param role 操作用户角色：0=学生，1=管理员，2=老师
     * @return 修改后的反馈VO
     */
    FeedbackVO updateFeedback(Long id, FeedbackSubmitDTO submitDTO, Long userId, Integer role);

    /**
     * 查询我的反馈列表（学生/老师端）
     * @param queryDTO 查询参数
     * @param userId 当前登录用户ID
     * @return 分页反馈列表VO
     */
    Page<FeedbackVO> queryMyFeedbackPage(FeedbackQueryDTO queryDTO, Long userId);
    /**
     * 获取反馈系统指标看板聚合真实统计数据（管理端）
     */
    java.util.Map<String, Long> getFeedbackStats();

    /**
     * 异步 AI 审核逻辑
     * @param feedbackId 反馈记录 ID
     */
    void asyncAiReview(Long feedbackId);
}
