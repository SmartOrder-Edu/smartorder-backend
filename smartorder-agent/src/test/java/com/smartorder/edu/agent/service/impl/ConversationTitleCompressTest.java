package com.smartorder.edu.agent.service.impl;

import com.smartorder.edu.agent.util.TitleNormalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话标题压缩回归测试——用真实导出的 {@code conversation_message} 全量数据，
 * 把每个会话的<b>首条用户消息</b>交给 {@link TitleNormalizer} 压成会话标题。
 *
 * <p><b>为什么用 SQL 导出文件而不是连库</b>：这是一次性/可重复的数据核对，
 * 连库会引入环境依赖（账号、网络、库是否还在）。导出文件本身就是数据快照，
 * 解析它就能让结果可复现、可在 CI 跑、也不污染线上库。</p>
 *
 * <p><b>压缩策略（两级）</b>：</p>
 * <ol>
 *   <li>一级：{@link TitleNormalizer#normalize(String)}——去前缀/引号/Markdown/结尾标点、
 *       折叠空白、超 24 字截断。返回 {@code null} 表示"这条输出不可用"。</li>
 *   <li>二级：回退截断——首条消息折叠成一行后取前 {@value #FALLBACK_MAX_LENGTH} 字，
 *       对应 {@code conversation.title} 的建表注释"默认取首条用户消息前30字"。</li>
 * </ol>
 *
 * <p><b>产物</b>：{@code docs/后端docs/conversation/title-compress/} 下的
 * 报告（md）、明细（csv）、回写脚本（sql），可直接拿去做数据订正。</p>
 *
 * <p>运行方式：{@code mvn -pl smartorder-agent test -Dtest=ConversationTitleCompressTest}
 * ；数据文件位置可用 {@code -Dsmartorder.dump.root=D:/path/to/project} 覆盖。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("会话标题压缩：conversation_message 首条用户消息 → 会话标题")
class ConversationTitleCompressTest {

    /** 回退标题长度，对齐 conversation.title 的建表注释"默认取首条用户消息前30字" */
    private static final int FALLBACK_MAX_LENGTH = 30;

    /** 报告里首条消息的展示长度 */
    private static final int PREVIEW_LENGTH = 60;

    private static final String MESSAGE_DUMP = "docs/后端docs/conversation/conversation_message.sql";
    private static final String CONVERSATION_DUMP = "docs/后端docs/conversation/conversation.sql";
    private static final String OUTPUT_DIR = "docs/后端docs/conversation/title-compress";

    private static Dump messageDump;
    private static Dump conversationDump;
    private static List<Row> messages;
    private static List<TitleResult> results;
    private static Map<String, ConversationMeta> existingTitles;
    /** 用户消息全部被逻辑删除的会话：不生成标题，避免把已删内容当标题 */
    private static List<String> skippedConversations;

    @BeforeAll
    static void loadAll() throws IOException {
        Path messagePath = resolveProjectFile(MESSAGE_DUMP);
        Path conversationPath = resolveProjectFile(CONVERSATION_DUMP);

        messageDump = SqlDump.parse(messagePath, "conversation_message");
        conversationDump = SqlDump.parse(conversationPath, "conversation");

        messages = messageDump.rows().stream().map(Row::of).toList();
        // conversation 表对外主键是 public_id（等于消息表的 conversation_id）
        existingTitles = conversationDump.rows().stream()
                .map(Row::of)
                .collect(Collectors.toMap(r -> r.column("public_id"),
                        r -> new ConversationMeta(r.column("title"), r.column("title_source")),
                        (a, b) -> a, LinkedHashMap::new));

        results = compressAll(messages);
        List<String> compressed = results.stream().map(TitleResult::conversationId).toList();
        skippedConversations = messages.stream()
                .map(Row::conversationId).distinct()
                .filter(id -> !compressed.contains(id))
                .sorted().toList();

        System.out.printf(Locale.ROOT,
                "[TitleCompress] 消息=%d 会话=%d 生成标题=%d 归一化=%d 回退=%d 跳过=%d 现有会话行=%d%n",
                messages.size(), messages.stream().map(Row::conversationId).distinct().count(),
                results.size(), count(Strategy.NORMALIZED), count(Strategy.TRUNCATED),
                skippedConversations.size(), existingTitles.size());
    }

    // ==================== 测试 ====================

    @Test
    @Order(1)
    @DisplayName("导出文件里的每一条 INSERT 都能完整解析，列名与建表语句一致")
    void shouldParseEveryInsertRow() {
        assertTrue(messageDump.columns().containsAll(
                        List.of("id", "conversation_id", "user_id", "role", "content", "create_time")),
                "建表语句缺少必需列，实际列=" + messageDump.columns());
        assertEquals(messageDump.insertCount(), messages.size(),
                "存在解析失败的 INSERT 行（字段数不匹配或字符串未闭合）");
        assertTrue(messages.size() > 1000, "解析出的消息数异常偏少：" + messages.size());

        // 每条消息的角色必须落在已知枚举内，否则说明字符串反转义出错（引号错位会把内容串到下一列）
        List<String> roles = messages.stream().map(Row::role).distinct().sorted().toList();
        assertEquals(List.of("assistant", "tool", "user"), roles, "出现未知角色，疑似解析错位");

        // 会话 ID 只允许字母数字与连字符（含压测造的 conv-loadtest-xxx），
        // 出现空格/引号说明 conversation_id 列串位了
        Pattern convId = Pattern.compile("^[0-9a-zA-Z][0-9a-zA-Z-]{0,35}$");
        long badIds = messages.stream().filter(r -> !convId.matcher(r.conversationId()).matches()).count();
        assertEquals(0, badIds, "有 " + badIds + " 条消息的 conversation_id 不合法");

        // 一个会话只能属于一个用户：串位会让同一 conversation_id 混进别的 user_id
        Map<String, Set<Long>> usersPerConv = messages.stream()
                .collect(Collectors.groupingBy(Row::conversationId,
                        Collectors.mapping(Row::userId, Collectors.toSet())));
        List<String> multiUser = usersPerConv.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        assertTrue(multiUser.isEmpty(), "这些会话混入了多个 user_id，疑似解析串位：" + multiUser);
    }

    @Test
    @Order(2)
    @DisplayName("每个会话都能用首条用户消息压出合法标题")
    void shouldCompressEveryConversationFirstUserMessage() {
        assertFalse(results.isEmpty(), "没有压缩出任何会话标题");

        List<String> problems = new ArrayList<>();
        for (TitleResult r : results) {
            String title = r.compressed().title();
            if (title == null || title.isBlank()) {
                problems.add(r.conversationId() + " -> 空标题");
                continue;
            }
            if (title.contains("\n") || title.contains("\r")) {
                problems.add(r.conversationId() + " -> 标题含换行: " + title);
            }
            int max = r.compressed().strategy() == Strategy.NORMALIZED
                    ? TitleNormalizer.MAX_TITLE_LENGTH : FALLBACK_MAX_LENGTH;
            if (title.length() > max) {
                problems.add(r.conversationId() + " -> 超长(" + title.length() + ">" + max + "): " + title);
            }
        }
        assertTrue(problems.isEmpty(), "标题不合格 " + problems.size() + " 条:\n"
                + String.join("\n", problems.subList(0, Math.min(10, problems.size()))));

        // 首条用户消息必须真的取自该会话最早的一条用户消息
        for (TitleResult r : results) {
            String earliest = messages.stream()
                    .filter(m -> m.conversationId().equals(r.conversationId()))
                    .filter(m -> "user".equals(m.role()))
                    .filter(m -> m.isDeleted() == 0)
                    .filter(m -> m.content() != null && !m.content().isBlank())
                    .min(Comparator.comparing(Row::createTime, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparingLong(Row::id))
                    .map(Row::content)
                    .orElse(null);
            assertEquals(earliest, r.firstMessage(),
                    "会话 " + r.conversationId() + " 取到的不是首条用户消息");
        }
    }

    @Test
    @Order(3)
    @DisplayName("只有 TitleNormalizer 判为不可用的超长消息才走回退截断")
    void shouldFallbackOnlyForRejectedMessage() {
        for (TitleResult r : results) {
            String message = r.firstMessage();
            // 与 TitleNormalizer 内部口径一致：先折叠空白，再判长度
            String collapsed = message.replaceAll("\\s+", " ").trim();
            boolean rejected = collapsed.length() > TitleNormalizer.MAX_RAW_OUTPUT_LENGTH
                    || collapsed.length() < TitleNormalizer.MIN_TITLE_LENGTH;
            if (r.compressed().strategy() == Strategy.TRUNCATED) {
                assertTrue(rejected, "会话 " + r.conversationId()
                        + " 被回退，但 TitleNormalizer 其实可用：" + r.compressed().title());
            } else {
                assertFalse(rejected, "会话 " + r.conversationId()
                        + " 未回退，但消息长度 " + message.length() + " 超出 TitleNormalizer 输入上限");
            }
        }

        // 真实数据的分布：绝大多数首条消息能被归一化，回退只应是少数（资源上下文注入这类长消息）
        assertTrue(count(Strategy.NORMALIZED) > count(Strategy.TRUNCATED),
                "回退比例过高，说明 TitleNormalizer 与真实输入不匹配");
    }

    @Test
    @Order(4)
    @DisplayName("输出压缩报告、明细 CSV 与标题回写 SQL")
    void shouldWriteReportAndUpdateSql() throws IOException {
        Path outputDir = resolveProjectFile(OUTPUT_DIR);
        Files.createDirectories(outputDir);

        Path report = outputDir.resolve("title-compress-report.md");
        Path csv = outputDir.resolve("conversation-titles.csv");
        Path sql = outputDir.resolve("update-conversation-title.sql");

        Files.writeString(report, buildReport(), StandardCharsets.UTF_8);
        Files.writeString(csv, buildCsv(), StandardCharsets.UTF_8);
        Files.writeString(sql, buildUpdateSql(), StandardCharsets.UTF_8);

        assertTrue(Files.size(report) > 1024, "报告内容为空");
        assertTrue(Files.size(sql) > 1024, "回写脚本内容为空");
        assertEquals(results.size() + 1, Files.readAllLines(csv, StandardCharsets.UTF_8).size(),
                "CSV 行数应为 表头 + 会话数");

        System.out.printf(Locale.ROOT, "[TitleCompress] 产物目录: %s%n", outputDir.toAbsolutePath());
    }

    // ==================== 压缩逻辑 ====================

    /**
     * 把首条用户消息压成标题：先走 {@link TitleNormalizer}，不可用时回退到截断。
     */
    static CompressedTitle compress(String message) {
        String normalized = TitleNormalizer.normalize(message);
        if (normalized != null) {
            return new CompressedTitle(normalized, Strategy.NORMALIZED);
        }
        return new CompressedTitle(truncate(message), Strategy.TRUNCATED);
    }

    /** 回退标题：折叠成一行后截前 30 字（对齐 conversation.title 的建表约定） */
    static String truncate(String message) {
        String oneLine = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= FALLBACK_MAX_LENGTH
                ? oneLine
                : oneLine.substring(0, FALLBACK_MAX_LENGTH).trim();
    }

    /** 每个会话取最早的一条未删除用户消息，逐条压缩 */
    private static List<TitleResult> compressAll(List<Row> rows) {
        Comparator<Row> byTime = Comparator
                .comparing(Row::createTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(Row::id);

        Map<String, List<Row>> grouped = rows.stream()
                .filter(r -> "user".equals(r.role()))
                .filter(r -> r.isDeleted() == 0)
                .filter(r -> r.content() != null && !r.content().isBlank())
                .collect(Collectors.groupingBy(Row::conversationId, LinkedHashMap::new, Collectors.toList()));

        List<TitleResult> list = new ArrayList<>(grouped.size());
        grouped.forEach((cid, items) -> {
            Row first = items.stream().min(byTime).orElseThrow();
            list.add(new TitleResult(cid, first.userId(), first.content(), compress(first.content())));
        });
        list.sort(Comparator.comparing(TitleResult::conversationId));
        return list;
    }

    private static long count(Strategy strategy) {
        return results.stream().filter(r -> r.compressed().strategy() == strategy).count();
    }

    // ==================== 报告生成 ====================

    private static String buildReport() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        long normalized = count(Strategy.NORMALIZED);
        long truncated = count(Strategy.TRUNCATED);
        int truncatedTo24 = (int) results.stream()
                .filter(r -> r.compressed().strategy() == Strategy.NORMALIZED)
                .filter(r -> r.firstMessage().replaceAll("\\s+", " ").trim().length()
                        > TitleNormalizer.MAX_TITLE_LENGTH)
                .count();

        sb.append("# 会话标题压缩报告\n\n");
        sb.append("- 数据源：`").append(MESSAGE_DUMP).append("`\n");
        sb.append("- 生成时间：").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- 生成方式：`ConversationTitleCompressTest`（`TitleNormalizer` 压缩首条用户消息）\n\n");

        sb.append("## 一、总览\n\n");
        sb.append("| 指标 | 数值 |\n|---|---|\n");
        sb.append("| 消息总数 | ").append(messages.size()).append(" |\n");
        sb.append("| 会话总数 | ").append(results.size() + skippedConversations.size()).append(" |\n");
        sb.append("| 生成标题的会话 | ").append(results.size()).append(" |\n");
        sb.append("| TitleNormalizer 归一化成功 | ").append(normalized).append(" |\n");
        sb.append("| 回退截断（超 60 字被判定为不可用） | ").append(truncated).append(" |\n");
        sb.append("| 其中因超过 24 字被截断 | ").append(truncatedTo24).append(" |\n");
        sb.append("| 跳过（用户消息全部逻辑删除） | ").append(skippedConversations.size()).append(" |\n");
        sb.append("| conversation 表中已存在的会话 | ").append(existingTitles.size()).append(" |\n\n");

        sb.append("## 二、压缩规则\n\n");
        sb.append("1. 取每个会话**最早的一条未删除用户消息**（`role='user'`、`is_deleted=0`，按 `create_time,id` 排序）。\n");
        sb.append("2. 交给 `TitleNormalizer.normalize()`：折叠空白、去 `标题：` 前缀、去首尾引号括号、")
                .append("去 Markdown 标记、去结尾标点，超过 ").append(TitleNormalizer.MAX_TITLE_LENGTH)
                .append(" 字截断。\n");
        sb.append("3. 原始消息超过 ").append(TitleNormalizer.MAX_RAW_OUTPUT_LENGTH)
                .append(" 字时 `normalize()` 判为跑偏并返回 `null`，此时回退为「首条消息折叠成一行后取前 ")
                .append(FALLBACK_MAX_LENGTH).append(" 字」，与建表注释一致。\n\n");

        sb.append("## 三、与现有 `conversation.title` 的对比\n\n");
        List<TitleResult> matched = results.stream()
                .filter(r -> existingTitles.containsKey(r.conversationId())).toList();
        long same = matched.stream()
                .filter(r -> Objects.equals(r.compressed().title(),
                        existingTitles.get(r.conversationId()).title())).count();
        sb.append("- 消息表与 conversation 表可对上的会话：").append(matched.size()).append(" 条\n");
        sb.append("- 压缩标题与现有标题完全一致：").append(same).append(" 条\n");
        sb.append("- 不一致：").append(matched.size() - same).append(" 条（下表列出，可据此决定是否回写）\n\n");
        if (!matched.isEmpty()) {
            sb.append("| 会话ID | 现有标题 | 压缩标题 | 标题来源 |\n|---|---|---|---|\n");
            for (TitleResult r : matched) {
                ConversationMeta meta = existingTitles.get(r.conversationId());
                String existing = meta.title() == null ? "(null)" : meta.title();
                String mark = Objects.equals(existing, r.compressed().title()) ? existing : "**" + existing + "**";
                sb.append("| `").append(r.conversationId()).append("` | ")
                        .append(cell(mark)).append(" | ")
                        .append(cell(r.compressed().title())).append(" | ")
                        .append(meta.titleSource()).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 四、需要人工关注的会话（走了回退截断）\n\n");
        List<TitleResult> fallback = results.stream()
                .filter(r -> r.compressed().strategy() == Strategy.TRUNCATED).toList();
        if (fallback.isEmpty()) {
            sb.append("无。全部会话都由 `TitleNormalizer` 正常压缩。\n\n");
        } else {
            sb.append("这些首条消息超过 ").append(TitleNormalizer.MAX_RAW_OUTPUT_LENGTH)
                    .append(" 字（多为 `[资源上下文]` 注入块），归一化会判定为跑偏，只能截断，标题可读性较差：\n\n");
            sb.append("| 会话ID | 消息字数 | 回退标题 |\n|---|---|---|\n");
            for (TitleResult r : fallback) {
                sb.append("| `").append(r.conversationId()).append("` | ")
                        .append(r.firstMessage().length()).append(" | ")
                        .append(cell(r.compressed().title())).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 五、未生成标题的会话\n\n");
        if (skippedConversations.isEmpty()) {
            sb.append("无。每个会话都有至少一条可见用户消息。\n\n");
        } else {
            sb.append("共 ").append(skippedConversations.size())
                    .append(" 个会话的用户消息**全部被逻辑删除**（`is_deleted=1`）。")
                    .append("这些会话不生成标题——已删除的内容不该再当标题展示，需要人工确认是否清理会话本身：\n\n");
            sb.append("| # | 会话ID |\n|---|---|\n");
            int n = 0;
            for (String id : skippedConversations) {
                sb.append("| ").append(++n).append(" | `").append(id).append("` |\n");
            }
            sb.append('\n');
        }

        sb.append("## 六、全量压缩结果\n\n");
        sb.append("| # | 会话ID | 首条用户消息（截断展示） | 压缩标题 | 策略 |\n|---|---|---|---|---|\n");
        int i = 0;
        for (TitleResult r : results) {
            sb.append("| ").append(++i).append(" | `").append(r.conversationId()).append("` | ")
                    .append(cell(preview(r.firstMessage()))).append(" | ")
                    .append(cell(r.compressed().title())).append(" | ")
                    .append(r.compressed().strategy() == Strategy.NORMALIZED ? "normalize" : "truncate")
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static String buildCsv() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("conversation_id,user_id,first_message_length,strategy,compressed_title,"
                + "existing_title,existing_title_source,first_message\n");
        for (TitleResult r : results) {
            ConversationMeta meta = existingTitles.get(r.conversationId());
            sb.append(csv(r.conversationId())).append(',')
                    .append(r.userId()).append(',')
                    .append(r.firstMessage().length()).append(',')
                    .append(r.compressed().strategy()).append(',')
                    .append(csv(r.compressed().title())).append(',')
                    .append(csv(meta == null ? "" : meta.title())).append(',')
                    .append(csv(meta == null ? "" : meta.titleSource())).append(',')
                    .append(csv(r.firstMessage())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 回写脚本：只覆盖 {@code title_source='AUTO'} 的会话。
     * 模型总结（LLM）与用户改名（USER）的标题必须保留，这是 {@code updateTitleIfAuto} 的同一约定。
     */
    private static String buildUpdateSql() {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("-- 会话标题回写脚本（由 ConversationTitleCompressTest 生成）\n");
        sb.append("-- 数据源: ").append(MESSAGE_DUMP).append("\n");
        sb.append("-- 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("-- 规则: 每个会话取首条用户消息，经 TitleNormalizer 压缩；")
                .append("仅覆盖 title_source='AUTO' 的会话，不动 LLM 总结与用户改名\n");
        sb.append("-- 会话数: ").append(results.size()).append("\n\n");
        sb.append("SET NAMES utf8mb4;\n\n");
        for (TitleResult r : results) {
            sb.append("UPDATE `conversation` SET `title` = '")
                    .append(sql(r.compressed().title())).append("'")
                    .append(" WHERE `public_id` = '").append(sql(r.conversationId())).append("'")
                    .append(" AND `title_source` = 'AUTO';\n");
        }
        return sb.toString();
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_LENGTH ? oneLine : oneLine.substring(0, PREVIEW_LENGTH) + "…";
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String csv(String text) {
        // 换行压成字面量 \n，保证一条会话占一行，Excel/pandas 打开不会错行
        String value = text == null ? "" : text.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sql(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("'", "\\'");
    }

    // ==================== 路径解析 ====================

    /**
     * 定位项目根目录：从 {@code user.dir} 向上找含 {@code docs/后端docs/conversation/conversation_message.sql} 的目录。
     * <p>测试既可能在 {@code smartorder-backend/smartorder-agent} 下跑（maven），
     * 也可能在仓库根目录下跑（IDE），逐级上溯比写死 {@code ../../..} 稳。</p>
     */
    private static Path projectRoot() {
        String root = System.getProperty("smartorder.dump.root");
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath();
        }
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve(MESSAGE_DUMP))) {
                return cur;
            }
        }
        throw new IllegalStateException("找不到数据文件: " + MESSAGE_DUMP + "（起点 " + dir
                + "），可用 -Dsmartorder.dump.root=<项目根目录> 指定");
    }

    private static Path resolveProjectFile(String relative) {
        return projectRoot().resolve(relative);
    }

    // ==================== 数据模型 ====================

    enum Strategy {
        /** TitleNormalizer 归一化成功 */
        NORMALIZED,
        /** TitleNormalizer 判为不可用，回退截断 */
        TRUNCATED
    }

    record CompressedTitle(String title, Strategy strategy) {
    }

    record TitleResult(String conversationId, long userId, String firstMessage, CompressedTitle compressed) {
    }

    record ConversationMeta(String title, String titleSource) {
    }

    /** 一条消息（只保留压缩标题需要的列） */
    record Row(long id, String conversationId, long userId, String role,
               String content, String createTime, int isDeleted, Map<String, String> raw) {

        static Row of(Map<String, String> values) {
            return new Row(
                    parseLong(values.get("id")),
                    values.get("conversation_id"),
                    parseLong(values.get("user_id")),
                    values.get("role"),
                    values.get("content"),
                    values.get("create_time"),
                    (int) parseLong(values.get("is_deleted")),
                    values);
        }

        String column(String name) {
            return raw.get(name);
        }

        private static long parseLong(String value) {
            if (value == null || value.isBlank() || "NULL".equalsIgnoreCase(value)) {
                return 0L;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    record Dump(String table, List<String> columns, List<Map<String, String>> rows, int insertCount) {
    }

    // ==================== SQL 导出解析 ====================

    /**
     * Navicat 导出的最小解析器：只认 `CREATE TABLE` 的列顺序与 `INSERT ... VALUES (...)` 行。
     * <p>不引第三方 SQL 解析器——导出格式稳定，手写一遍还能顺带校验字段数是否对得上。</p>
     */
    static final class SqlDump {

        private static final Pattern COLUMN_DEF = Pattern.compile("^\\s*`(\\w+)`", Pattern.MULTILINE);

        private SqlDump() {
        }

        static Dump parse(Path path, String table) throws IOException {
            String sql = Files.readString(path, StandardCharsets.UTF_8);

            int createIdx = sql.indexOf("CREATE TABLE `" + table + "`");
            if (createIdx < 0) {
                throw new IllegalStateException("找不到建表语句: " + table + " in " + path);
            }
            int createEnd = sql.indexOf("\n) ENGINE", createIdx);
            String ddl = createEnd > 0
                    ? sql.substring(createIdx, createEnd)
                    : sql.substring(createIdx, sql.indexOf(';', createIdx));

            List<String> columns = new ArrayList<>();
            Matcher m = COLUMN_DEF.matcher(ddl);
            while (m.find()) {
                columns.add(m.group(1));
            }

            String prefix = "INSERT INTO `" + table + "` VALUES (";
            List<Map<String, String>> rows = new ArrayList<>();
            int insertCount = 0;
            int pos = 0;
            while ((pos = sql.indexOf(prefix, pos)) >= 0) {
                insertCount++;
                Tuple tuple = readTuple(sql, pos + prefix.length());
                pos = tuple.end();
                if (tuple.values().size() != columns.size()) {
                    // 字段数不匹配说明解析错位（引号未闭合），直接跳过，交给测试断言暴露
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    values.put(columns.get(i), tuple.values().get(i));
                }
                rows.add(values);
            }
            return new Dump(table, List.copyOf(columns), List.copyOf(rows), insertCount);
        }

        /**
         * 从 {@code (} 之后开始读一个 VALUES 元组，直到未加引号的右括号。
         * <p>字符串字面量按 MySQL 规则反转义：{@code \n \r \t \0 \b \Z \\ \' \" \% \_}，
         * 其余未知转义保留反斜杠原样（如正则里的 {@code \d}）。</p>
         */
        private static Tuple readTuple(String sql, int from) {
            List<String> values = new ArrayList<>();
            int i = from;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < sql.length()) {
                        char ch = sql.charAt(i);
                        if (ch == '\\' && i + 1 < sql.length()) {
                            sb.append(unescape(sql.charAt(i + 1)));
                            i += 2;
                        } else if (ch == '\'') {
                            i++;
                            break;
                        } else {
                            sb.append(ch);
                            i++;
                        }
                    }
                    values.add(sb.toString());
                } else if (c == 'N' && sql.startsWith("NULL", i)) {
                    values.add(null);
                    i += 4;
                } else if (c == ',') {
                    i++;
                } else if (c == ')') {
                    i++;
                    break;
                } else if (Character.isWhitespace(c)) {
                    i++;
                } else {
                    int start = i;
                    while (i < sql.length() && sql.charAt(i) != ',' && sql.charAt(i) != ')') {
                        i++;
                    }
                    values.add(sql.substring(start, i).trim());
                }
            }
            return new Tuple(values, i);
        }

        private static String unescape(char c) {
            return switch (c) {
                case '0' -> "\0";
                case 'b' -> "\b";
                case 'n' -> "\n";
                case 'r' -> "\r";
                case 't' -> "\t";
                case 'Z' -> "\u001A";
                case '\\' -> "\\";
                case '\'' -> "'";
                case '"' -> "\"";
                case '%' -> "%";
                case '_' -> "_";
                default -> "\\" + c;
            };
        }

        private record Tuple(List<String> values, int end) {
        }
    }
}
