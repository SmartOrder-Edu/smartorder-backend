package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.service.ConversationTitleService;
import com.smartorder.edu.agent.service.TitleGeneratorAgent;
import com.smartorder.edu.agent.util.TitleInputExtractor;
import com.smartorder.edu.agent.util.TitleNormalizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧会话标题回填——把历史会话的"截断标题"重新生成成 DeepSeek 式标签标题。
 *
 * <p><b>为什么要单独做一个回填</b>：线上标题生成只在会话<b>首条消息</b>时跑一次，
 * 而且早期版本用的是"摘要式"提示词（{@code 你好给我讲解一下双token机制企业级前后端实现}）。
 * 历史数据不会自己变好，必须用新的 {@code TitleInputExtractor + title-generator.md + TitleNormalizer}
 * 重新跑一遍。</p>
 *
 * <p><b>处理范围</b>：{@code status='ACTIVE'} 且 {@code title_source IN ('AUTO','LLM')}。
 * 用户手动改过名的（{@code USER}）<b>永远不动</b>——那是用户的意志，不该被机器覆盖。</p>
 *
 * <p><b>输入取几条消息</b>：默认取该会话前 3 条未删除的用户消息。首条常常是"你好"，
 * 只给它一条消息，标题永远是"新对话"；把后续两条接上才能拿到真实主题。
 * 首条本身够具体时只用首条，不引入无关噪音。</p>
 *
 * <p><b>安全设计</b>：</p>
 * <ol>
 *   <li>默认 <b>只生成不写库</b>，产出报告 / CSV / UPDATE 脚本，人工确认后再执行；</li>
 *   <li>写库必须显式传 {@code -Dsmartorder.backfill.apply=true}；</li>
 *   <li>写库前自动建备份表 {@code conversation_title_bak_yyyyMMdd}，回滚一条 SQL；</li>
 *   <li>结果缓存到 {@code title-backfill.jsonl}，重跑直接复用，不重复烧 token（{@code -Dforce=true} 可强制重算）。</li>
 * </ol>
 *
 * <p><b>运行方式</b>（在 {@code smartorder-backend} 目录）：</p>
 * <pre>
 * # 1) 只生成，不写库
 * ./mvnw -o -pl smartorder-agent test -Dtest=LegacyConversationTitleBackfillTest
 *
 * # 2) 生成并写回数据库
 * ./mvnw -o -pl smartorder-agent test -Dtest=LegacyConversationTitleBackfillTest -Dsmartorder.backfill.apply=true
 *
 * # 可选参数
 * -Dsmartorder.backfill.force=true          忽略缓存重算
 * -Dsmartorder.backfill.concurrency=4       并发数（默认 4）
 * -Dsmartorder.backfill.limit=10            只处理前 N 个会话（先试水）
 * </pre>
 *
 * <p>数据库与模型凭据沿用应用配置的环境变量：{@code mysql_username} / {@code mysql_password} /
 * {@code DEEPSEEK_API_KEY}；也可用 {@code -Dsmartorder.db.url=...} 覆盖连接串。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("旧会话标题回填：重新生成标签式标题")
class LegacyConversationTitleBackfillTest {

    /** 产物目录（相对项目根） */
    private static final String OUTPUT_DIR = "docs/后端docs/conversation/title-backfill";

    /** 结果缓存：断点续跑 + 审计，重跑不再重复调模型 */
    private static final String CACHE_FILE = "title-backfill.jsonl";

    /** 默认取前几条用户消息作为上下文 */
    private static final int MAX_CONTEXT_MESSAGES = 3;

    /** 首条消息短于此长度（或等于"新对话"）时才拼接后续消息 */
    private static final int MIN_PRIMARY_LENGTH = 6;

    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://117.72.222.3:3306/smart_order_db"
                    + "?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String DEFAULT_LLM_BASE_URL = "https://api.deepseek.com/v1";

    /** 与 {@code agent.llm.title-model-name} 保持一致；可用 -Dsmartorder.llm.model 覆盖 */
    private static final String DEFAULT_LLM_MODEL = "deepseek-flash";

    private static List<Plan> plans;
    private static List<Result> results;

    // ==================== 生命周期 ====================

    @BeforeAll
    static void prepare() throws Exception {
        Assumptions.assumeTrue(dbUsername() != null && dbPassword() != null,
                "缺少 mysql_username / mysql_password，跳过回填");
        Assumptions.assumeTrue(llmApiKey() != null,
                "缺少 DEEPSEEK_API_KEY，跳过回填");

        plans = loadPlans();
        Assumptions.assumeFalse(plans.isEmpty(), "没有需要回填的会话");

        System.out.printf(Locale.ROOT, "[Backfill] 待处理会话 %d 个，开始生成标题...%n", plans.size());
        long start = System.currentTimeMillis();
        results = generateAll(plans);
        System.out.printf(Locale.ROOT, "[Backfill] 生成完成，耗时 %dms%n",
                System.currentTimeMillis() - start);
    }

    // ==================== 测试 ====================

    @Test
    @Order(1)
    @DisplayName("生成标题并产出报告 / CSV / 回写脚本（不写库）")
    void shouldGenerateAndWriteArtifacts() throws IOException {
        Path dir = outputDir();
        Files.createDirectories(dir);

        Files.writeString(dir.resolve(CACHE_FILE), buildCacheJsonl(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("title-backfill-report.md"), buildReport(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("conversation-titles.csv"), buildCsv(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("update-conversation-title.sql"), buildUpdateSql(), StandardCharsets.UTF_8);

        // 生成结果自检：标题必须是"能直接贴到列表里"的形态
        List<String> problems = new ArrayList<>();
        for (Result r : results) {
            if (r.strategy() == Strategy.SKIPPED) {
                continue;
            }
            String title = r.newTitle();
            if (title == null || title.isBlank()) {
                problems.add(r.publicId() + " -> 空标题");
            } else if (title.contains("\n") || title.contains("\r")) {
                problems.add(r.publicId() + " -> 含换行: " + title);
            } else if (title.length() > TitleNormalizer.MAX_TITLE_LENGTH) {
                problems.add(r.publicId() + " -> 超长(" + title.length() + "): " + title);
            }
        }
        assertTrue(problems.isEmpty(), "标题不合格 " + problems.size() + " 条:\n"
                + String.join("\n", problems.subList(0, Math.min(10, problems.size()))));

        System.out.printf(Locale.ROOT, "[Backfill] 产物目录: %s%n", dir.toAbsolutePath());
        System.out.printf(Locale.ROOT, "[Backfill] %s%n", summaryLine());
    }

    @Test
    @Order(2)
    @DisplayName("apply=true 时写回数据库（先建备份表）")
    void shouldApplyToDatabaseWhenEnabled() throws SQLException {
        Assumptions.assumeTrue(Boolean.getBoolean("smartorder.backfill.apply"),
                "未开启 -Dsmartorder.backfill.apply=true，跳过写库");

        List<Result> changes = results.stream()
                .filter(r -> r.strategy() == Strategy.LLM || r.strategy() == Strategy.FALLBACK)
                .toList();
        Assumptions.assumeFalse(changes.isEmpty(), "没有需要写回的标题");

        String backupTable = "conversation_title_bak_"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // 备份只做一次：重复执行时保留第一次的原始值，别把备份覆盖成"已改过的值"
                st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + backupTable + "` AS "
                        + "SELECT conversation_id, title, title_source, NOW() AS backup_at FROM conversation");
                System.out.printf(Locale.ROOT, "[Backfill] 备份表: %s%n", backupTable);
            }

            int updated = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversation SET title = ?, title_source = ? "
                            + "WHERE conversation_id = ? AND title_source IN ('AUTO','LLM')")) {
                for (Result r : changes) {
                    ps.setString(1, r.newTitle());
                    // 兜底标题不是模型产物，来源仍记 AUTO，语义上才不会被误当成"模型总结"
                    ps.setString(2, r.strategy() == Strategy.LLM ? "LLM" : "AUTO");
                    ps.setString(3, r.publicId());
                    updated += ps.executeUpdate();
                }
            }
            conn.commit();
            System.out.printf(Locale.ROOT, "[Backfill] 已写回 %d 行（目标 %d 行）%n",
                    updated, changes.size());

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT title_source, COUNT(*) FROM conversation GROUP BY title_source")) {
                while (rs.next()) {
                    System.out.printf(Locale.ROOT, "[Backfill] 回写后分布: %s=%d%n",
                            rs.getString(1), rs.getInt(2));
                }
            }
            System.out.printf(Locale.ROOT,
                    "[Backfill] 回滚（如需）: UPDATE conversation c JOIN `%s` b ON b.conversation_id = c.conversation_id "
                            + "SET c.title = b.title, c.title_source = b.title_source;%n", backupTable);
        }
    }

    // ==================== 数据加载 ====================

    /** 待处理会话：ACTIVE 且标题来源不是用户改名 */
    private static List<Plan> loadPlans() throws SQLException {
        Map<String, List<String>> messages = loadFirstUserMessages();
        List<Plan> list = new ArrayList<>();

        String sql = """
                SELECT conversation_id, title, title_source
                FROM conversation
                WHERE status = 'ACTIVE' AND title_source IN ('AUTO', 'LLM')
                ORDER BY id
                """;
        int limit = Integer.getInteger("smartorder.backfill.limit", 0);

        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (limit > 0 && list.size() >= limit) {
                    break;
                }
                String publicId = rs.getString(1);
                String oldTitle = rs.getString(2);
                String oldSource = rs.getString(3);
                List<String> raw = messages.getOrDefault(publicId, List.of());
                list.add(new Plan(publicId, oldTitle, oldSource, raw.size(), buildInput(raw)));
            }
        }
        return list;
    }

    /**
     * 每个会话前 {@value #MAX_CONTEXT_MESSAGES} 条未删除用户消息。
     * <p>用窗口函数取序号，而不是 MIN(id)：id 虽是雪花递增，但按 create_time 更贴合"对话顺序"。</p>
     */
    private static Map<String, List<String>> loadFirstUserMessages() throws SQLException {
        String sql = """
                SELECT conversation_id, content FROM (
                    SELECT conversation_id, content,
                           ROW_NUMBER() OVER (PARTITION BY conversation_id
                                              ORDER BY create_time, id) AS rn
                    FROM conversation_message
                    WHERE role = 'user' AND is_deleted = 0
                      AND content IS NOT NULL AND content <> ''
                ) t
                WHERE rn <= ?
                ORDER BY conversation_id, rn
                """;

        Map<String, List<String>> map = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MAX_CONTEXT_MESSAGES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        return map;
    }

    /**
     * 组装送进模型的输入。
     * <p>首条消息够具体（如"我想学习数据结构"）就只用它；首条是"你好/在吗"这种，
     * 才把后续消息接上——否则标题只能是"新对话"。</p>
     */
    private static String buildInput(List<String> rawMessages) {
        List<String> parts = rawMessages.stream()
                .map(TitleInputExtractor::extract)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (parts.isEmpty()) {
            return "";
        }

        String primary = parts.get(0);
        boolean tooVague = primary.length() < MIN_PRIMARY_LENGTH
                || TitleNormalizer.isPlaceholder(TitleNormalizer.fallbackTitle(primary));
        if (!tooVague || parts.size() == 1) {
            return primary;
        }

        StringBuilder sb = new StringBuilder(primary);
        for (int i = 1; i < parts.size(); i++) {
            if (sb.length() + parts.get(i).length() > TitleInputExtractor.MAX_INPUT_LENGTH) {
                break;
            }
            sb.append('\n').append(parts.get(i));
        }
        return sb.toString();
    }

    // ==================== 标题生成 ====================

    private static List<Result> generateAll(List<Plan> plans) throws Exception {
        Map<String, String> cache = loadCache();
        boolean force = Boolean.getBoolean("smartorder.backfill.force");
        int concurrency = Math.max(1, Integer.getInteger("smartorder.backfill.concurrency", 4));

        List<Result> out = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<Result>> futures = new ArrayList<>(plans.size());
            for (Plan plan : plans) {
                futures.add(pool.submit(() -> {
                    Result r = generateOne(plan, cache, force);
                    int n = done.incrementAndGet();
                    System.out.printf(Locale.ROOT, "[Backfill] %d/%d %s -> %s (%s)%n",
                            n, plans.size(), shorten(plan.oldTitle()), r.newTitle(), r.strategy());
                    if (r.strategy() == Strategy.FALLBACK || r.strategy() == Strategy.SKIPPED) {
                        System.out.printf(Locale.ROOT, "[Backfill]   输入: %s%n   模型输出: %s%n",
                                shorten(plan.input()), r.reason());
                    }
                    return r;
                }));
            }
            for (Future<Result> f : futures) {
                out.add(f.get());
            }
        } finally {
            pool.shutdown();
        }
        return out;
    }

    private static Result generateOne(Plan plan, Map<String, String> cache, boolean force) {
        if (plan.input().isBlank()) {
            // 纯附件会话：没有文字可压缩，保留原样
            return new Result(plan, plan.oldTitle(), Strategy.SKIPPED, "");
        }

        String cached = cache.get(plan.publicId());
        if (!force && cached != null && !cached.isBlank()) {
            return new Result(plan, cached, Strategy.LLM, "cache");
        }

        String title = null;
        List<String> rejected = new ArrayList<>();
        for (int attempt = 0; attempt < 2 && title == null; attempt++) {
            try {
                String raw = attempt == 0
                        ? agent().generateTitle(plan.input())
                        : agent().generateTitle(ConversationTitleServiceImpl.RETRY_PREFIX + plan.input());
                String normalized = TitleNormalizer.normalize(raw);
                if (normalized != null && !TitleNormalizer.isPlaceholder(normalized)
                        && !TitleNormalizer.isEcho(normalized, plan.input())) {
                    title = normalized;
                } else {
                    rejected.add(oneLine(raw));
                }
            } catch (Exception e) {
                rejected.add("ERROR: " + e.getMessage());
            }
        }

        if (title == null) {
            // 模型两次都不可用：至少把 30 字截断换成标签式兜底，比原样好
            String fallback = TitleNormalizer.fallbackTitle(plan.input());
            String reason = String.join(" | ", rejected);
            if (TitleNormalizer.isPlaceholder(fallback)
                    || Objects.equals(fallback, plan.oldTitle())) {
                return new Result(plan, plan.oldTitle(), Strategy.SKIPPED, reason);
            }
            return new Result(plan, fallback, Strategy.FALLBACK, reason);
        }

        if (Objects.equals(title, plan.oldTitle())) {
            return new Result(plan, plan.oldTitle(), Strategy.UNCHANGED, "");
        }
        return new Result(plan, title, Strategy.LLM, "");
    }

    private static volatile TitleGeneratorAgent agent;

    private static TitleGeneratorAgent agent() {
        if (agent == null) {
            synchronized (LegacyConversationTitleBackfillTest.class) {
                if (agent == null) {
                    String prompt = readClasspath("prompts/system/title-generator.md");
                    ChatModel model = OpenAiChatModel.builder()
                            .apiKey(llmApiKey())
                            .baseUrl(System.getProperty("smartorder.llm.baseUrl", DEFAULT_LLM_BASE_URL))
                            .modelName(System.getProperty("smartorder.llm.model", DEFAULT_LLM_MODEL))
                            .temperature(0.3)
                            .maxTokens(128)
                            // 必须显式关思考：deepseek-v4-flash 默认思考，
                            // 64 个 token 会被 reasoning_tokens 吃光，content 返回空串
                            .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                            .timeout(Duration.ofSeconds(30))
                            .build();
                    agent = AiServices.builder(TitleGeneratorAgent.class)
                            .chatModel(model)
                            .systemMessageProvider(memoryId -> prompt)
                            .build();
                }
            }
        }
        return agent;
    }

    // ==================== 产物生成 ====================

    private static String buildCacheJsonl() {
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            JSONObject o = new JSONObject();
            o.put("publicId", r.publicId());
            o.put("oldTitle", r.oldTitle());
            o.put("oldSource", r.oldSource());
            o.put("newTitle", r.newTitle());
            o.put("strategy", r.strategy().name());
            o.put("reason", r.reason());
            o.put("input", r.input());
            sb.append(o.toJSONString()).append('\n');
        }
        return sb.toString();
    }

    private static String buildReport() {
        long llm = count(Strategy.LLM);
        long fallback = count(Strategy.FALLBACK);
        long unchanged = count(Strategy.UNCHANGED);
        long skipped = count(Strategy.SKIPPED);

        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("# 旧会话标题回填报告\n\n");
        sb.append("- 数据源：`").append(dbUrl().replaceAll("\\?.*$", "")).append("`\n");
        sb.append("- 生成时间：").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- 生成方式：`LegacyConversationTitleBackfillTest`（")
                .append(System.getProperty("smartorder.llm.model", DEFAULT_LLM_MODEL)).append("）\n");
        sb.append("- 处理范围：`status='ACTIVE' AND title_source IN ('AUTO','LLM')`，用户改名（USER）不处理\n\n");

        sb.append("## 一、总览\n\n");
        sb.append("| 指标 | 数量 |\n|---|---|\n");
        sb.append("| 待处理会话 | ").append(results.size()).append(" |\n");
        sb.append("| 模型生成新标题 | ").append(llm).append(" |\n");
        sb.append("| 模型失败，用本地兜底标题 | ").append(fallback).append(" |\n");
        sb.append("| 与原标题一致（无需变更） | ").append(unchanged).append(" |\n");
        sb.append("| 跳过（无有效用户消息） | ").append(skipped).append(" |\n");
        sb.append("| **实际会变更** | ").append(llm + fallback).append(" |\n\n");

        sb.append("## 二、新旧标题对比\n\n");
        sb.append("| # | 会话ID | 旧标题 | 新标题 | 策略 |\n|---|---|---|---|---|\n");
        int i = 0;
        for (Result r : results) {
            sb.append("| ").append(++i).append(" | `").append(r.publicId()).append("` | ")
                    .append(cell(r.oldTitle())).append(" | ")
                    .append(cell(r.newTitle())).append(" | ")
                    .append(r.strategy()).append(" |\n");
        }
        sb.append('\n');

        List<Result> needAttention = results.stream()
                .filter(r -> r.strategy() == Strategy.FALLBACK || r.strategy() == Strategy.SKIPPED)
                .toList();
        sb.append("## 三、需要人工看一眼的会话\n\n");
        if (needAttention.isEmpty()) {
            sb.append("无。所有会话都由模型给出了可用标题。\n\n");
        } else {
            sb.append("这些会话模型两次都没给出合格标题（多为「背景自述」类消息，模型倾向于复述原话），")
                    .append("这里用本地兜底压缩或保持原样。输入与模型原始输出一并列出，便于调提示词：\n\n");
            sb.append("| 会话ID | 输入（前 60 字） | 当前标题 | 模型原始输出 |\n|---|---|---|---|\n");
            for (Result r : needAttention) {
                sb.append("| `").append(r.publicId()).append("` | ")
                        .append(cell(shorten(r.input()))).append(" | ")
                        .append(cell(r.newTitle())).append(" | ")
                        .append(cell(shorten(r.reason()))).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 四、怎么执行\n\n");
        sb.append("1. 先看上面这张表，确认标题符合预期；\n");
        sb.append("2. 想直接写库：重跑并加 `-Dsmartorder.backfill.apply=true`（会自动建备份表）；\n");
        sb.append("3. 想手工执行：`update-conversation-title.sql` 里是等价的 UPDATE 语句，")
                .append("只覆盖 `title_source IN ('AUTO','LLM')`；\n");
        sb.append("4. 回滚：从 `conversation_title_bak_yyyyMMdd` 备份表按 `conversation_id` 恢复。\n");
        return sb.toString();
    }

    private static String buildCsv() {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("public_id,old_source,old_title,new_title,strategy,input\n");
        for (Result r : results) {
            sb.append(csv(r.publicId())).append(',')
                    .append(csv(r.oldSource())).append(',')
                    .append(csv(r.oldTitle())).append(',')
                    .append(csv(r.newTitle())).append(',')
                    .append(r.strategy()).append(',')
                    .append(csv(r.input())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 回写脚本：只覆盖 AUTO / LLM，且带上旧标题做对照，便于 diff 时确认改的是哪一条。
     */
    private static String buildUpdateSql() {
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("-- 旧会话标题回填（由 LegacyConversationTitleBackfillTest 生成）\n");
        sb.append("-- 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("-- 规则: 只覆盖 title_source IN ('AUTO','LLM')，用户改名（USER）不动\n");
        sb.append("-- 变更条数: ").append(count(Strategy.LLM) + count(Strategy.FALLBACK)).append("\n\n");
        sb.append("SET NAMES utf8mb4;\n\n");
        for (Result r : results) {
            if (r.strategy() != Strategy.LLM && r.strategy() != Strategy.FALLBACK) {
                continue;
            }
            String source = r.strategy() == Strategy.LLM ? "LLM" : "AUTO";
            sb.append("-- 旧: ").append(oneLine(r.oldTitle())).append('\n');
            sb.append("UPDATE `conversation` SET `title` = '").append(sql(r.newTitle()))
                    .append("', `title_source` = '").append(source).append('\'')
                    .append(" WHERE `conversation_id` = '").append(sql(r.publicId())).append('\'')
                    .append(" AND `title_source` IN ('AUTO','LLM');\n\n");
        }
        return sb.toString();
    }

    private static String summaryLine() {
        return String.format(Locale.ROOT,
                "会话=%d 模型生成=%d 兜底=%d 未变=%d 跳过=%d",
                results.size(), count(Strategy.LLM), count(Strategy.FALLBACK),
                count(Strategy.UNCHANGED), count(Strategy.SKIPPED));
    }

    private static long count(Strategy strategy) {
        return results.stream().filter(r -> r.strategy() == strategy).count();
    }

    // ==================== 缓存 ====================

    private static Map<String, String> loadCache() {
        Map<String, String> cache = new LinkedHashMap<>();
        Path file = outputDir().resolve(CACHE_FILE);
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JSONObject o = JSON.parseObject(line);
                String id = o.getString("publicId");
                String title = o.getString("newTitle");
                String strategy = o.getString("strategy");
                if (id != null && title != null && !title.isBlank()
                        && ("LLM".equals(strategy) || "FALLBACK".equals(strategy))) {
                    cache.put(id, title);
                }
            }
        } catch (IOException e) {
            System.out.printf(Locale.ROOT, "[Backfill] 缓存读取失败，忽略: %s%n", e.getMessage());
        }
        return cache;
    }

    // ==================== 基础设施 ====================

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(dbUrl(), dbUsername(), dbPassword());
    }

    private static String dbUrl() {
        return System.getProperty("smartorder.db.url",
                env("SMARTORDER_DB_URL", DEFAULT_DB_URL));
    }

    private static String dbUsername() {
        return System.getProperty("smartorder.db.username", System.getenv("mysql_username"));
    }

    private static String dbPassword() {
        return System.getProperty("smartorder.db.password", System.getenv("mysql_password"));
    }

    private static String llmApiKey() {
        return System.getProperty("smartorder.llm.apiKey", System.getenv("DEEPSEEK_API_KEY"));
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String readClasspath(String resource) {
        try (InputStream in = LegacyConversationTitleBackfillTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 里找不到 " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + resource + " 失败", e);
        }
    }

    private static Path outputDir() {
        return projectRoot().resolve(OUTPUT_DIR);
    }

    /**
     * 定位项目根：从 {@code user.dir} 向上找含 dump 文件的目录。
     * <p>maven 下 user.dir 是模块目录，IDE 下可能是仓库根，逐级上溯比写死相对路径稳。</p>
     */
    private static Path projectRoot() {
        String root = System.getProperty("smartorder.dump.root");
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath();
        }
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("docs/后端docs/conversation/conversation.sql"))) {
                return cur;
            }
        }
        throw new IllegalStateException("找不到项目根（起点 " + dir + "），可用 -Dsmartorder.dump.root 指定");
    }

    // ==================== 小工具 ====================

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String csv(String text) {
        String value = text == null ? "" : text.replace("\r\n", "\\n")
                .replace("\n", "\\n").replace("\r", "\\n");
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sql(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ");
    }

    private static String shorten(String text) {
        String s = oneLine(text);
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    // ==================== 数据模型 ====================

    /** 一条待回填会话 */
    record Plan(String publicId, String oldTitle, String oldSource, int messageCount, String input) {
    }

    /** 一条回填结果 */
    record Result(Plan plan, String newTitle, Strategy strategy, String reason) {
        String publicId() {
            return plan.publicId();
        }

        String oldTitle() {
            return plan.oldTitle();
        }

        String oldSource() {
            return plan.oldSource();
        }

        String input() {
            return plan.input();
        }
    }

    enum Strategy {
        /** 模型生成 */
        LLM,
        /** 模型失败，本地兜底压缩 */
        FALLBACK,
        /** 与新标题一致，无需变更 */
        UNCHANGED,
        /** 没有有效用户消息，保持原样 */
        SKIPPED
    }
}
