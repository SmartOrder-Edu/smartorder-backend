package com.smartorder.edu.student.service.impl;

import com.smartorder.edu.student.config.EvaluationRedisConstants;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.mapper.EvaluationAnswerRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 答题草稿异步批量刷 MySQL 调度器
 *
 * <p>每 30 秒扫描一次待同步的答题草稿，批量 upsert 到
 * {@code evaluation_answer_record} 表（status=1，存档状态）。
 * </p>
 *
 * <p>触发条件：{@link com.smartorder.edu.student.service.impl.EvaluationSessionServiceImpl#saveProgress}
 * 写入 Redis Hash 后同时将 sessionId 加入待同步集合。</p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftAnswerSyncScheduler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final EvaluationAnswerRecordMapper answerRecordMapper;

    /** 待同步 Session 集合 Key */
    static final String PENDING_SYNC_SET = EvaluationRedisConstants.PREFIX + "draft:sync:pending";

    @Scheduled(fixedDelay = 30_000)
    public void flushDraftToDatabase() {
        // 1. 获取待同步的 sessionId 集合
        Set<Object> pendingSessionIds = redisTemplate.opsForSet().members(PENDING_SYNC_SET);
        if (pendingSessionIds == null || pendingSessionIds.isEmpty()) {
            return;
        }

        log.debug("开始批量刷新草稿，待同步 session 数: {}", pendingSessionIds.size());

        for (Object obj : pendingSessionIds) {
            Long sessionId = toLong(obj);
            if (sessionId == null) {
                redisTemplate.opsForSet().remove(PENDING_SYNC_SET, obj);
                continue;
            }
            try {
                flushSessionDrafts(sessionId);
                // 成功后从待同步集合移除
                redisTemplate.opsForSet().remove(PENDING_SYNC_SET, obj);
            } catch (Exception e) {
                log.error("刷新草稿失败: sessionId={}", sessionId, e);
                // 保留在集合中，下次重试
            }
        }
    }

    /**
     * 刷新单个 session 的所有草稿到 MySQL
     */
    private void flushSessionDrafts(Long sessionId) {
        // 扫描所有用户的草稿：这里需要知道哪些用户有草稿
        // 策略：从 sync flag key 推导。由于我们在 saveProgress 时同时设置了
        // evaluation:session:{sessionId}:draft:sync:flag，这里通过 Redis 扫描获取。

        // 使用 keys 扫描该 session 下的所有 draft Hash key
        String draftKeyPattern = String.format(
                EvaluationRedisConstants.SESSION_DRAFT, sessionId, 0L)
                .replace(":draft:0", ":draft:*");
        var draftKeys = redisTemplate.keys(draftKeyPattern);
        if (draftKeys == null || draftKeys.isEmpty()) {
            return;
        }

        for (String draftKey : draftKeys) {
            flushOneUserDrafts(sessionId, draftKey);
        }
    }

    /**
     * 刷新单个用户在该 session 下的所有草稿
     */
    private void flushOneUserDrafts(Long sessionId, String draftKey) {
        // 读取 Hash 全部字段
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(draftKey);
        if (entries.isEmpty()) {
            return;
        }

        int upserted = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            Long questionId = toLong(entry.getKey());
            Object value = entry.getValue();
            if (questionId == null || value == null) continue;

            try {
                // value 是 DraftAnswerVO 的 JSON 表示（通过 GenericJackson2JsonRedisSerializer）
                // 这里直接通过 answerRecordMapper 做 upsert
                upsertDraftRecord(sessionId, questionId, value);
                upserted++;
            } catch (Exception e) {
                log.error("刷新单条草稿失败: sessionId={}, questionId={}", sessionId, questionId, e);
            }
        }

        if (upserted > 0) {
            log.info("草稿已刷新到 MySQL: sessionId={}, key={}, count={}", sessionId, draftKey, upserted);
            // 刷新后删除该 Hash 的同步标记（保留 Hash 数据供后续读取）
        }
    }

    @SuppressWarnings("unchecked")
    private void upsertDraftRecord(Long sessionId, Long questionId, Object draftData) {
        Map<String, Object> draft;
        if (draftData instanceof Map) {
            draft = (Map<String, Object>) draftData;
        } else {
            log.warn("草稿数据格式异常: sessionId={}, questionId={}", sessionId, questionId);
            return;
        }

        // 查找已有记录
        var records = answerRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvaluationAnswerRecord>()
                        .eq(EvaluationAnswerRecord::getSessionId, sessionId)
                        .eq(EvaluationAnswerRecord::getQuestionId, questionId)
                        .eq(EvaluationAnswerRecord::getStatus, 1));

        EvaluationAnswerRecord record;
        if (!records.isEmpty()) {
            record = records.get(0);
        } else {
            record = new EvaluationAnswerRecord();
            record.setSessionId(sessionId);
            record.setTaskId(sessionId);
            record.setQuestionId(questionId);
            record.setStatus(1); // 存档
            record.setCreatedAt(LocalDateTime.now());
        }

        // 更新字段
        if (draft.containsKey("userAnswer")) {
            record.setUserAnswer((Map<String, Object>) draft.get("userAnswer"));
        }
        if (draft.containsKey("duration")) {
            Object dur = draft.get("duration");
            record.setDuration(dur instanceof Number n ? n.intValue() : 0);
        }
        if (draft.containsKey("usedHint")) {
            Object hint = draft.get("usedHint");
            record.setUsedHint(hint instanceof Number n ? n.intValue() : 0);
        }
        record.setUpdatedAt(LocalDateTime.now());

        if (record.getId() != null) {
            answerRecordMapper.updateById(record);
        } else {
            answerRecordMapper.insert(record);
        }
    }

    private Long toLong(Object obj) {
        if (obj instanceof Long l) return l;
        if (obj instanceof Integer i) return i.longValue();
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
