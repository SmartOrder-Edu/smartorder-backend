package com.smartorder.edu.agent.task;

import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 超时占位消息清理。
 * <p>
 * 场景：服务端进程被杀 / 重启 / 线程池被回收，导致 assistant 占位行永远停在 RUNNING，
 * 前端会一直显示"正在生成…"。
 * <p>
 * 处理：每分钟扫一次，把 started_at 超过 5 分钟仍是 RUNNING 的行置为 FAILED，
 * 前端加载历史时就能看到"生成中断，点击重试"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleMessageCleaner {

    /** 生成超时阈值（分钟）——需大于 SSE 超时（180s）与计划执行超时（300s）的常规耗时 */
    private static final int TIMEOUT_MINUTES = 5;

    private final ConversationMessageMapper messageMapper;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void cleanZombieMessages() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        try {
            int affected = messageMapper.markZombieFailed(threshold);
            if (affected > 0) {
                log.warn("[StaleMessage] 清理超时占位消息: count={}, threshold={}", affected, threshold);
            }
        } catch (Exception e) {
            // 清理失败不影响主链路
            log.error("[StaleMessage] 清理失败", e);
        }
    }
}
