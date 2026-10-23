package com.smartorder.edu.student.config;

import java.time.Duration;

/**
 * 测评模块 Redis 缓存 Key 模板与 TTL 常量
 *
 * <p>Key 前缀统一为 {@code evaluation:}，与知识库、文件等其他业务模块隔离。
 * 各 TTL 加随机 ±20% 抖动防止缓存雪崩，实际使用时通过
 * {@link com.smartorder.edu.student.service.impl.EvaluationCacheService#randomizeTtl(Duration)} 计算。
 * </p>
 *
 * @author SmartOrder
 * @since 2026-07-11
 */
public final class EvaluationRedisConstants {

    /** 全局 Key 前缀 */
    public static final String PREFIX = "evaluation:";

    // ==================== Key 模板 ====================

    /** 题目列表（按学习空间 + 限制数量） */
    public static final String QUESTIONS_SPACE = PREFIX + "questions:space:%d:limit:%d";

    /** 题目列表（按生成任务 ID） */
    public static final String QUESTIONS_TASK = PREFIX + "questions:task:%d";

    /** 试卷题目（按测评会话） */
    public static final String SESSION_QUESTIONS = PREFIX + "session:%d:questions";

    /** 判分结果（按会话 + 用户） */
    public static final String SESSION_RESULT = PREFIX + "session:%d:result:%d";

    /** 学习报告（按会话 + 用户） */
    public static final String SESSION_REPORT = PREFIX + "session:%d:report:%d";

    /** 答题草稿 Hash（按会话 + 用户，field = questionId） */
    public static final String SESSION_DRAFT = PREFIX + "session:%d:draft:%d";

    /** 草稿同步标记 Key（存在即表示该 session 有未刷新的草稿） */
    public static final String SESSION_DRAFT_SYNC_FLAG = PREFIX + "session:%d:draft:sync:flag";

    // ==================== 缓存穿透防护 Key ====================

    /** 空值占位 Key 后缀（避免 key 冲突） */
    public static final String EMPTY_PLACEHOLDER_SUFFIX = ":empty";

    // ==================== TTL 定义 ====================

    /** 题目列表缓存：30 分钟 */
    public static final Duration TTL_QUESTIONS = Duration.ofMinutes(30);

    /** 试卷题目缓存：60 分钟（考试期间不变） */
    public static final Duration TTL_SESSION_QUESTIONS = Duration.ofMinutes(60);

    /** 判分结果缓存：5 分钟 */
    public static final Duration TTL_RESULT = Duration.ofMinutes(5);

    /** 学习报告缓存：30 分钟 */
    public static final Duration TTL_REPORT = Duration.ofMinutes(30);

    /** 答题草稿缓存：2 小时 */
    public static final Duration TTL_DRAFT = Duration.ofHours(2);

    /** 空值缓存：5 分钟（防穿透） */
    public static final Duration TTL_EMPTY = Duration.ofMinutes(5);

    /** 报告生成中占位符 TTL：2 分钟 */
    public static final Duration TTL_REPORT_GENERATING = Duration.ofMinutes(2);

    // ==================== Key 构建工具方法 ====================

    public static String questionsSpaceKey(Long spaceId, Integer limit) {
        return String.format(QUESTIONS_SPACE, spaceId, limit);
    }

    public static String questionsTaskKey(Long taskId) {
        return String.format(QUESTIONS_TASK, taskId);
    }

    public static String sessionQuestionsKey(Long sessionId) {
        return String.format(SESSION_QUESTIONS, sessionId);
    }

    public static String sessionResultKey(Long sessionId, Long userId) {
        return String.format(SESSION_RESULT, sessionId, userId);
    }

    public static String sessionReportKey(Long sessionId, Long userId) {
        return String.format(SESSION_REPORT, sessionId, userId);
    }

    public static String sessionDraftKey(Long sessionId, Long userId) {
        return String.format(SESSION_DRAFT, sessionId, userId);
    }

    public static String sessionDraftSyncFlagKey(Long sessionId) {
        return String.format(SESSION_DRAFT_SYNC_FLAG, sessionId);
    }

    private EvaluationRedisConstants() {
        // 工具类禁止实例化
    }
}
