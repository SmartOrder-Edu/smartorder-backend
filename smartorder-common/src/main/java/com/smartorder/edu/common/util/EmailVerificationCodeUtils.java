package com.smartorder.edu.common.util;


import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.exception.SystemException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.util.validation.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 邮件验证码服务类（Redis存储版）
 * 负责生成、发送、验证和管理验证码
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@Component
public class EmailVerificationCodeUtils {
    /**
     * Redis存储前缀
     */
    private static final String REDIS_KEY_PREFIX = "email:code:";
    /**
     * 验证码有效期（分钟）
     */
    private static final long CODE_EXPIRE_MINUTES = 10;
    /**
     * 验证码长度
     */
    private static final int CODE_LENGTH = 6;
    /**
     * 发送间隔限制（秒）- 防止频繁发送
     */
    private static final long SEND_INTERVAL_SECONDS = 60;
    /**
     * 发件人名称
     */
    private static final String SENDER_NAME = "知序智学";


    /**
     * 邮件服务器配置
     */
    @Value("${email.host}")
    private String SMTP_HOST;
    @Value("${email.port}")
    private String SMTP_PORT;
    @Value("${email.username}")
    private String SMTP_USER;
    @Value("${email.password}")
    private String SMTP_PASSWORD; // SMTP授权码

    /**
     * Redis模板
     */
    private final StringRedisTemplate stringRedisTemplate;


    public EmailVerificationCodeUtils(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成并发送验证码（有频率控制）
     * @param email 目标邮箱
     * @param userIP 用户IP地址
     * @throws BusinessException 邮件发送异常
     */
    public void generateAndSendCode(String email,String userIP){
        String redisKey = REDIS_KEY_PREFIX + email;
        // 检查是否存在未过期的验证码且处于冷却期
        Object lastSendTimeObject = stringRedisTemplate.opsForHash().get(redisKey,"lastSendTime");
        if (lastSendTimeObject != null) {
            String lastSendTimeStr =  (String) lastSendTimeObject;
            long lastSendTime = Long.parseLong(lastSendTimeStr);
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastSendTime < TimeUnit.SECONDS.toMillis(SEND_INTERVAL_SECONDS)) {
                long remaining = (TimeUnit.SECONDS.toMillis(SEND_INTERVAL_SECONDS) - (currentTime - lastSendTime)) / 1000;
                log.warn("验证码发送过于频繁，用户ip:{}",userIP);
                throw new BusinessException("请不要频繁发送验证码，等待" + remaining + "秒后重试");
            }
        }

        // 生成验证码
        String code;
        // 生成新的验证码
        code = generateRandomCode();
        // 存储验证码信息到Redis
        long currentTime = System.currentTimeMillis();
        stringRedisTemplate.opsForHash().put(redisKey, "code", code);
        stringRedisTemplate.opsForHash().put(redisKey, "createTime", String.valueOf(currentTime));
        // 更新最后发送时间
        stringRedisTemplate.opsForHash().put(redisKey, "lastSendTime", String.valueOf(currentTime));
        // 设置过期时间
        stringRedisTemplate.expire(redisKey, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

        // 发送邮件
        try {
            sendEmail(email, code);
        }catch (MessagingException | UnsupportedEncodingException e){
            log.error("系统异常：发送邮箱验证码失败, 用户邮箱: {}, 原因: {}", email, e.getMessage(), e);
            stringRedisTemplate.delete(redisKey);
            throw new SystemException("系统繁忙，验证码发送失败，请稍后重试");
        }
    }

    /**
     * 验证验证码
     * @param email 邮箱
     * @param code 待验证的验证码
     */
    public void verifyCode(String email, String code) {
        String redisKey = REDIS_KEY_PREFIX + email;
        String storedCode = (String) stringRedisTemplate.opsForHash().get(redisKey, "code");

        // 验证码不存在或不匹配
        if (storedCode == null || !storedCode.equals(code)) {
            throw new ValidationException("验证码错误或已过期");
        }

        // 验证成功后移除验证码，防止重复使用
        removeCode(email);
    }

    /**
     * 移除指定邮箱的验证码
     * @param email 邮箱
     */
    public void removeCode(String email) {
        stringRedisTemplate.delete(REDIS_KEY_PREFIX + email);
    }

    /**
     * 生成随机验证码
     * @return 数字验证码
     */
    private String generateRandomCode() {
        Random rand = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(rand.nextInt(10));
        }
        return code.toString();
    }

    /**
     * 发送邮件
     * @param toEmail 接收邮箱
     * @param code 验证码
     * @throws MessagingException 邮件发送异常
     */
    private void sendEmail(String toEmail, String code) throws MessagingException, UnsupportedEncodingException {
        // 配置邮件服务器
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.timeout", "5000"); // 超时时间

        // 创建会话
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USER, SMTP_PASSWORD);
            }
        });
        session.setDebug(false); // 生产环境关闭调试

        // 创建邮件消息
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SMTP_USER, SENDER_NAME, "UTF-8"));
        message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(toEmail));
        message.setSubject(SENDER_NAME + "验证码", "UTF-8");
        message.setSentDate(new java.util.Date());

        // 邮件内容
        String content = String.format(
                "【%s】您的验证码是：<strong>%s</strong><br/>" +
                        "该验证码10分钟内有效，请尽快完成验证。<br/>" +
                        "如非本人操作，请忽略此邮件。",
                SENDER_NAME, code
        );
        message.setContent(content, "text/html;charset=UTF-8");

        // 发送邮件
        Transport.send(message);
    }
}