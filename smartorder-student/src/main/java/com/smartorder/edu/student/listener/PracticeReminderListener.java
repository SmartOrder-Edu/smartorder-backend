package com.smartorder.edu.student.listener;

import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.common.util.EmailUtils;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.mail.MessagingException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 刷题提醒 RabbitMQ 消费者
 * <p>
 * 监听处理队列，收到消息后校验 session 状态，确认仍需提醒后发送邮件。
 * 消息经由 TTL + Dead Letter Exchange 延迟到达，无需定时轮询。
 * </p>
 *
 * <pre>
 * stage{N}.queue (TTL) → DLX → process.queue → 此 Listener
 * </pre>
 *
 * @author SmartOrder
 * @since 2026-07-10
 */
@Slf4j
@Component
@RabbitListener(queues = "practice.reminder.process.queue")
@RequiredArgsConstructor
public class PracticeReminderListener {

    private final EmailUtils emailUtils;
    private final EvaluationSessionMapper sessionMapper;
    private final UserMapper userMapper;

    /**
     * 处理到达处理队列的提醒消息
     *
     * @param msg 消息体，包含 sessionId、userId、stage
     */
    @RabbitHandler
    public void handleReminder(Map<String, Object> msg) {
        // 1. 解析消息
        Long sessionId = toLong(msg.get("sessionId"));
        Long userId = toLong(msg.get("userId"));
        Integer stage = msg.get("stage") instanceof Integer
                ? (Integer) msg.get("stage") : null;

        if (sessionId == null || userId == null || stage == null) {
            log.warn("提醒消息格式异常，丢弃: {}", msg);
            return;
        }

        log.info("收到刷题提醒消息: sessionId={}, userId={}, stage={}", sessionId, userId, stage);

        // 2. 校验 session 状态：只有 doing 状态才发提醒
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.info("Session 已不存在，跳过提醒: sessionId={}", sessionId);
            return;
        }
        if (!"doing".equals(session.getStatus())) {
            log.info("Session 已不在进行中状态（status={}），跳过提醒: sessionId={}",
                    session.getStatus(), sessionId);
            return;
        }

        // 3. 获取用户信息
        User user = userMapper.selectById(userId);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("用户无邮箱，跳过提醒: userId={}", userId);
            return;
        }

        // 4. 发送邮件
        try {
            String subject = buildSubject(stage);
            String content = buildContent(user, session, stage);
            emailUtils.sendEmail(user.getEmail(), subject, content);
            log.info("刷题提醒邮件已发送: sessionId={}, userId={}, email={}, stage={}",
                    sessionId, userId, user.getEmail(), stage);
        } catch (MessagingException e) {
            log.error("邮件发送失败（将触发 MQ 重试）: sessionId={}, userId={}, email={}",
                    sessionId, userId, user.getEmail(), e);
            throw new RuntimeException("邮件发送失败", e); // 抛出异常触发 RabbitMQ 重试机制
        }
    }

    /**
     * Jackson 反序列化时数字可能为 Integer，统一转为 Long
     */
    private Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.valueOf((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 构建邮件主题
     */
    private String buildSubject(int stage) {
        if (stage == 1) {
            return "【知序智学】你的刷题任务还在等你哦～";
        }
        return "【知序智学】别让你的刷题任务等太久～";
    }

    /**
     * 构建邮件内容（HTML）
     */
    private String buildContent(User user, EvaluationSession session, int stage) {
        String username = user.getUsername() != null ? user.getUsername() : "同学";
        String topic = session.getTopic() != null ? session.getTopic() : "未命名任务";
        String timePassed = formatTimePassed(session.getStartedAt());

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>");
        sb.append("<div style=\"max-width:600px;margin:0 auto;padding:20px;font-family:Arial,sans-serif;\">");

        // 头部
        sb.append("<div style=\"background:#4A90D9;padding:20px;border-radius:8px 8px 0 0;text-align:center;\">");
        sb.append("<h1 style=\"color:#fff;margin:0;font-size:22px;\">知序智学</h1>");
        sb.append("<p style=\"color:#D6E4F0;margin:8px 0 0;font-size:14px;\">AI 自适应学习平台</p>");
        sb.append("</div>");

        // 正文
        sb.append("<div style=\"background:#fff;padding:24px;border:1px solid #e0e0e0;border-top:none;\">");
        sb.append("<p style=\"font-size:16px;color:#333;\">Hi <b>").append(username).append("</b>，</p>");

        if (stage == 1) {
            sb.append("<p style=\"font-size:15px;color:#555;line-height:1.8;\">");
            sb.append("你的刷题任务 <b style=\"color:#4A90D9;\">「").append(topic).append("」</b> 已经开始了 <b>")
                    .append(timePassed).append("</b>，看起来你还没有完成哦～<br>");
            sb.append("学习贵在坚持，<b>每天进步一点点</b>，积累起来就是巨大的成长！<br>");
            sb.append("打开知序智学，继续你的答题之旅吧 💪");
            sb.append("</p>");
        } else {
            sb.append("<p style=\"font-size:15px;color:#555;line-height:1.8;\">");
            sb.append("你的刷题任务 <b style=\"color:#E8874A;\">「").append(topic).append("」</b> 已经等待了 <b>")
                    .append(timePassed).append("</b>，是时候回来完成它了！<br>");
            sb.append("拖延是学习最大的敌人，<b>现在行动</b>，你会发现比想象中简单得多。<br>");
            sb.append("每一道题都是向前的一步，别让你的任务变成未完成的遗憾 ⏰");
            sb.append("</p>");
        }

        sb.append("<div style=\"background:#F5F7FA;padding:16px;border-radius:8px;margin:20px 0;\">");
        sb.append("<p style=\"font-size:14px;color:#666;margin:0;\">");
        sb.append("📋 <b>任务信息</b><br>");
        sb.append("任务名称：").append(topic).append("<br>");
        sb.append("开始时间：").append(session.getStartedAt() != null
                ? session.getStartedAt().toString().replace("T", " ") : "未知").append("<br>");
        sb.append("已过时间：").append(timePassed);
        sb.append("</p></div>");

        sb.append("<div style=\"text-align:center;margin:24px 0;\">");
        sb.append("<a href=\"#\" style=\"display:inline-block;background:#4A90D9;color:#fff;padding:12px 32px;");
        sb.append("text-decoration:none;border-radius:6px;font-size:15px;\">继续答题</a>");
        sb.append("</div>");

        sb.append("<p style=\"font-size:13px;color:#999;text-align:center;\">");
        sb.append("如果你已经完成了这个任务，可以忽略这封邮件。<br>");
        sb.append("此为系统自动发送的提醒邮件，请勿回复。");
        sb.append("</p>");
        sb.append("</div>");

        // 底部
        sb.append("<div style=\"text-align:center;padding:12px;font-size:12px;color:#aaa;\">");
        sb.append("知序智学 · SmartOrder · AI 驱动的自适应学习");
        sb.append("</div>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
     * 格式化已过时间
     */
    private String formatTimePassed(LocalDateTime startedAt) {
        if (startedAt == null) {
            return "一段时间";
        }
        Duration duration = Duration.between(startedAt, LocalDateTime.now());
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;

        if (hours >= 24) {
            long days = hours / 24;
            long remainHours = hours % 24;
            if (remainHours > 0) {
                return days + " 天 " + remainHours + " 小时";
            }
            return days + " 天";
        }
        if (hours > 0) {
            return hours + " 小时 " + (minutes > 0 ? minutes + " 分钟" : "");
        }
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        return "不到 1 分钟";
    }
}
