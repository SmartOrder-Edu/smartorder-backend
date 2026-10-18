package com.smartorder.edu.manage.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.manage.domain.dto.FeedbackQueryDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackReviewDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackSubmitDTO;
import com.smartorder.edu.manage.domain.po.Feedback;
import com.smartorder.edu.manage.domain.vo.FeedbackVO;
import com.smartorder.edu.manage.mapper.FeedbackMapper;
import com.smartorder.edu.manage.service.FeedbackAiAnalysisService;
import com.smartorder.edu.manage.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.context.ApplicationEventPublisher;
import com.smartorder.edu.manage.listener.FeedbackSubmittedEvent;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements FeedbackService {

    private final FeedbackAiAnalysisService aiAnalysisService;
    private final com.smartorder.edu.manage.service.PrizeService prizeService;
    private final com.smartorder.edu.manage.producer.PrizeMessageProducer prizeMessageProducer;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submitFeedback(FeedbackSubmitDTO submitDTO, Long userId) {
        Feedback Feedback = new Feedback();
        Feedback.setUserId(userId);
        Feedback.setType(submitDTO.getType());
        Feedback.setContent(submitDTO.getContent());
        Feedback.setTitle(submitDTO.getTitle());
        Feedback.setContact(submitDTO.getContact());
        if (submitDTO.getImageUrls() != null && !submitDTO.getImageUrls().isEmpty()) {
            Feedback.setImageUrls(String.join(",", submitDTO.getImageUrls()));
        }
        Feedback.setCreateTime(LocalDateTime.now());
        Feedback.setUpdateTime(LocalDateTime.now());
        
        // 1. 设置初始状态：待AI初审 (0)
        Feedback.setStatus(0);
        
        // 2. 保存入库（数据库持久化优先，保障用户数据绝不丢失）
        this.save(Feedback);
        
        // 3. 发布异步事件：由 Spring Event 异步解耦初审，消除 HTTP 阻塞延时
        eventPublisher.publishEvent(new FeedbackSubmittedEvent(this, Feedback.getId()));
        log.info("用户提交反馈成功并已安全入库，已发布异步AI审核事件. feedbackId={}", Feedback.getId());
        
        // 返回VO
        return convertToVO(Feedback);
    }

    @Override
    public Page<FeedbackVO> queryFeedbackPage(FeedbackQueryDTO queryDTO) {
        Page<Feedback> pageParam = new Page<>(queryDTO.getPage(), queryDTO.getSize());
        LambdaQueryWrapper<Feedback> wrapper = Wrappers.lambdaQuery(Feedback.class)
                .select(Feedback.class, info -> !info.getColumn().equals("ai_analysis_result"))
                .eq(queryDTO.getType() != null, Feedback::getType, queryDTO.getType())
                .eq(queryDTO.getStatus() != null, Feedback::getStatus, queryDTO.getStatus())
                .orderByDesc(Feedback::getCreateTime);

        Page<Feedback> poPage = this.page(pageParam, wrapper);
        
        Page<FeedbackVO> voPage = new Page<>(poPage.getCurrent(), poPage.getSize(), poPage.getTotal());
        List<FeedbackVO> voList = poPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO reviewFeedback(Long id, FeedbackReviewDTO reviewDTO) {
        Feedback Feedback = this.getById(id);
        if (Feedback == null) {
            throw new IllegalArgumentException("反馈记录不存在，id: " + id);
        }

        // 只能人工审核：3=人工审核通过（有效反馈），4=人工审核驳回，5=已采纳
        Integer newStatus = reviewDTO.getStatus();
        if (newStatus != 3 && newStatus != 4 && newStatus != 5) {
            throw new IllegalArgumentException("无效的审核目标状态：" + newStatus);
        }

        Feedback.setStatus(newStatus);
        Feedback.setReviewRemark(reviewDTO.getReviewRemark());
        // 管理员答复内容与答复时间
        if (reviewDTO.getReplyContent() != null && !reviewDTO.getReplyContent().trim().isEmpty()) {
            Feedback.setReplyContent(reviewDTO.getReplyContent());
            Feedback.setReplyTime(LocalDateTime.now());
        }
        Feedback.setUpdateTime(LocalDateTime.now());
        
        this.updateById(Feedback);

        // 触发对应的业务通知与奖惩逻辑：状态 3 只代表有效反馈，状态 5 才代表采纳并赠送卡密。
        if (newStatus == 4) {
            sendMockNotification(Feedback.getUserId(), "您的反馈未通过人工复核，原因：" + reviewDTO.getReviewRemark());
        } else if (newStatus == 3) {
            log.info("用户反馈已通过人工复核，暂不发放卡密奖励，用户ID: {}, 反馈ID: {}", Feedback.getUserId(), Feedback.getId());
        } else if (newStatus == 5) {
            log.info("用户反馈被采纳，用户ID: {}, 反馈ID: {}。开始尝试安全分配卡密奖励...", Feedback.getUserId(), Feedback.getId());
            // 1. 原子排他锁安全出库卡密
            String prizeCode = prizeService.allocatePrize(Feedback.getId(), Feedback.getUserId());
            if (prizeCode != null) {
                // 将兑换码追加到官方回复中，前端局部刷新时可直接展示完整闭环结果。
                String baseReply = Feedback.getReplyContent() == null ? "" : Feedback.getReplyContent();
                Feedback.setReplyContent(baseReply + "\n【系统发放奖品：兑换码：" + prizeCode + "】");
                Feedback.setUpdateTime(LocalDateTime.now());
                this.updateById(Feedback);

                // 2. 构造卡密邮件消息模型
                com.smartorder.edu.manage.domain.dto.PrizeMailMessage mailMessage = new com.smartorder.edu.manage.domain.dto.PrizeMailMessage();
                mailMessage.setFeedbackId(Feedback.getId());
                mailMessage.setUserId(Feedback.getUserId());
                mailMessage.setPrizeName("【特等采纳奖】京东卡 100 元");
                mailMessage.setPrizeCode(prizeCode);
                mailMessage.setEmail(Feedback.getContact()); // 用反馈联系方式（如邮箱）作为派发终点
                
                // 3. 投递进入 RabbitMQ 异步派信管道
                prizeMessageProducer.sendPrizeMailMessage(mailMessage);
            } else {
                log.warn("无法派发反馈兑换奖励：系统奖品库库存已经告罄！请管理员及时在后台补充库存。");
            }
        }

        return convertToVO(Feedback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFeedback(Long id, Long userId, Integer role) {
        Feedback po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("该反馈记录不存在");
        }

        // 管理员 (role=1) 可以删任何数据。学生 (role=0) 或 老师 (role=2) 只能删自己的反馈
        if (role != 1 && !po.getUserId().equals(userId)) {
            throw new IllegalStateException("您无权删除该反馈记录");
        }

        this.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO updateFeedback(Long id, FeedbackSubmitDTO submitDTO, Long userId, Integer role) {
        Feedback po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("该反馈记录不存在");
        }

        // 管理员 (role=1) 可以修改任何数据。学生 (role=0) 或 老师 (role=2) 只能修改自己的反馈
        if (role != 1 && !po.getUserId().equals(userId)) {
            throw new IllegalStateException("您无权修改该反馈记录");
        }

        // 学生/老师端非管理员仅能在 待AI初审(0) 或 AI通过/待复核(1) 的状态下进行修改
        if (role != 1 && po.getStatus() != 0 && po.getStatus() != 1) {
            throw new IllegalStateException("当前反馈状态下无法进行修改");
        }

        // 修改反馈内容与图片
        po.setType(submitDTO.getType());
        po.setContent(submitDTO.getContent());
        po.setTitle(submitDTO.getTitle());
        po.setContact(submitDTO.getContact());
        if (submitDTO.getImageUrls() != null && !submitDTO.getImageUrls().isEmpty()) {
            po.setImageUrls(String.join(",", submitDTO.getImageUrls()));
        } else {
            po.setImageUrls(null);
        }

        // 修改反馈后必须重新触发 AI 初审评分，防止用户篡改绕过 AI 过滤
        po.setStatus(0);
        po.setAiAnalysisResult(null);
        po.setReviewRemark(null);
        po.setUpdateTime(LocalDateTime.now());

        this.updateById(po);
        
        // 发布异步 AI 初审事件（与提交反馈公用一套高吞吐异步管道）
        eventPublisher.publishEvent(new FeedbackSubmittedEvent(this, po.getId()));
        log.info("修改反馈成功并保存入库，已发布重新AI审核事件. feedbackId={}", po.getId());
        
        return convertToVO(po);
    }

    @Override
    public Page<FeedbackVO> queryMyFeedbackPage(FeedbackQueryDTO queryDTO, Long userId) {
        Page<Feedback> pageParam = new Page<>(queryDTO.getPage(), queryDTO.getSize());
        LambdaQueryWrapper<Feedback> wrapper = Wrappers.lambdaQuery(Feedback.class)
                .select(Feedback.class, info -> !info.getColumn().equals("ai_analysis_result"))
                .eq(Feedback::getUserId, userId)
                .eq(queryDTO.getType() != null, Feedback::getType, queryDTO.getType())
                .eq(queryDTO.getStatus() != null, Feedback::getStatus, queryDTO.getStatus())
                .orderByDesc(Feedback::getCreateTime);

        Page<Feedback> poPage = this.page(pageParam, wrapper);
        
        Page<FeedbackVO> voPage = new Page<>(poPage.getCurrent(), poPage.getSize(), poPage.getTotal());
        List<FeedbackVO> voList = poPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        
        return voPage;
    }

    /**
     * 辅助转换方法：PO -> VO
     */
    private FeedbackVO convertToVO(Feedback po) {
        FeedbackVO vo = new FeedbackVO();
        BeanUtil.copyProperties(po, vo);
        if (po.getImageUrls() != null && !po.getImageUrls().trim().isEmpty()) {
            vo.setImageUrls(Arrays.asList(po.getImageUrls().split(",")));
        }
        return vo;
    }

    /**
     * 模拟消息通知/邮件发送
     */
    private void sendMockNotification(Long userId, String message) {
        log.info("[模拟邮件/系统通知] 向用户(ID: {}) 发送通知：\"{}\"", userId, message);
    }

    @Override
    public java.util.Map<String, Long> getFeedbackStats() {
        // 高效 SQL 聚合查询：改用通用 QueryWrapper 完美兼容原生 SQL COUNT(*) 聚合，MySQL 中利用覆盖索引瞬间完成
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Feedback> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        queryWrapper.select("status", "COUNT(*) as cnt")
                .eq("is_deleted", 0)
                .groupBy("status");

        List<java.util.Map<String, Object>> maps = this.listMaps(queryWrapper);

        long pendingReview = 0;
        long aiRejected = 0;
        long accepted = 0;

        for (java.util.Map<String, Object> map : maps) {
            Integer status = (Integer) map.get("status");
            Long count = ((Number) map.get("cnt")).longValue();
            if (status == 0 || status == 1) pendingReview += count;
            else if (status == 2) aiRejected = count;
            else if (status == 5) accepted = count;
        }

        java.util.Map<String, Long> statsMap = new java.util.HashMap<>();
        statsMap.put("pendingReview", pendingReview);
        statsMap.put("aiRejected", aiRejected);
        statsMap.put("accepted", accepted);
        return statsMap;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void asyncAiReview(Long feedbackId) {
        Feedback Feedback = this.getById(feedbackId);
        if (Feedback == null) {
            log.warn("异步 AI 审核未找到反馈记录. feedbackId={}", feedbackId);
            return;
        }

        // 只有状态为待AI初审 (0) 的记录才运行 AI 审核
        if (Feedback.getStatus() != 0) {
            log.info("当前反馈记录已完成初审或状态已改变, 终止异步 AI 审核. feedbackId={}, status={}", feedbackId, Feedback.getStatus());
            return;
        }

        log.info(">>>>>> 开启 AI 智能评测过滤. feedbackId={} <<<<<<", feedbackId);
        // 调用AI进行初审（通过网络调用第三方大模型，耗时耗IO，此方法已在异步线程池中运行）
        String aiResultStr = aiAnalysisService.analyzeFeedback(Feedback.getContent(), Feedback.getType());
        Feedback.setAiAnalysisResult(aiResultStr);
        Feedback.setUpdateTime(LocalDateTime.now());

        try {
            JSONObject aiResult = JSONUtil.parseObj(aiResultStr);
            Boolean isValid = aiResult.getBool("isValid");
            
            // 根据 AI 初审结果更新状态
            if (Boolean.TRUE.equals(isValid)) {
                // AI初审通过，流转到待人工复核 (1)
                Feedback.setStatus(1);
                log.info("AI初审通过. feedbackId={}, score={}", feedbackId, aiResult.getInt("score", 0));
            } else {
                // AI初审驳回 (2)
                Feedback.setStatus(2);
                Feedback.setReviewRemark("系统AI自动拦截: " + aiResult.getStr("reason"));
                log.warn("AI初审自动拦截驳回. feedbackId={}, 驳回理由={}", feedbackId, aiResult.getStr("reason"));
            }
        } catch (Exception e) {
            log.error("解析AI审核结果发生异常, 采用安全降级策略流转为人工复核. json={}, feedbackId={}", aiResultStr, Feedback.getId(), e);
            // 降级：转人工复核
            Feedback.setStatus(1);
            Feedback.setReviewRemark("系统AI自动拦截: 评测超时或结果解析异常，安全降级至人工审核");
        }

        // 更新数据库
        this.updateById(Feedback);
        log.info(">>>>>> 异步 AI 审核完成. feedbackId={}, 最终状态为={} <<<<<<", feedbackId, Feedback.getStatus());
    }
}
