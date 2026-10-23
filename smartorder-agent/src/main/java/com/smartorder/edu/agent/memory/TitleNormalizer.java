package com.smartorder.edu.agent.memory;

import java.util.regex.Pattern;

/**
 * 会话标题清洗器——把模型输出规整成可直接落库的"标签式"标题。
 *
 * <p>模型（尤其是未强制 JSON 输出的对话模型）常见跑偏：</p>
 * <ul>
 *   <li>加前缀："标题：xxx"、"会话标题: xxx"</li>
 *   <li>加包裹："「xxx」"、"'xxx'"、"**xxx**"</li>
 *   <li>加结尾标点："xxx。"、"xxx？"</li>
 *   <li>保留口语开头："帮我xxx"、"请问xxx"、"我想xxx"</li>
 *   <li>保留语气尾巴："xxx吗"、"xxx呢"、"xxx吧"</li>
 *   <li>输出成解释/分析："用户希望xxx，因此标题为xxx"</li>
 * </ul>
 *
 * <p>本类全是纯函数：{@link #normalize(String)} 返回 {@code null} 表示
 * "这次输出不可用"；{@link #fallbackTitle(String)} 是本地兜底，永远返回一个可展示的标题。</p>
 */
public final class TitleNormalizer {

    /**
     * 标题最大字符数。
     * <p>提示词要求 ≤24，这里留到 28：中英混排的技术标题（{@code Claude Code用TypeScript开发}）
     * 按字符算很容易到 26，卡 24 会把最后一个词切掉。超过 28 才截断。</p>
     */
    public static final int MAX_TITLE_LENGTH = 28;

    /** 短于此长度视为无效输出 */
    public static final int MIN_TITLE_LENGTH = 2;

    /** 原始输出超过此长度说明跑偏了（输出成解释/分析），直接判为无效 */
    public static final int MAX_RAW_OUTPUT_LENGTH = 60;

    /** 本地兜底标题的最大长度——比模型标题更短，列表里一行放得下 */
    public static final int FALLBACK_TITLE_LENGTH = 14;

    /** 没有实质内容时统一使用的占位标题 */
    public static final String PLACEHOLDER = "新对话";

    /** 开头的 Markdown 标记：# 标题、> 引用、- 列表 */
    private static final Pattern LEADING_MARKDOWN = Pattern.compile("^[#>*\\-]+\\s*");

    /** "标题：" / "会话标题:" 前缀 */
    private static final Pattern TITLE_PREFIX = Pattern.compile("^(会话)?标题\\s*[:：]\\s*");

    /** 首尾包裹的引号与括号（中英文） */
    private static final Pattern LEADING_QUOTES =
            Pattern.compile("^[\"'“”‘’「」『』《》\\[\\]()（）]+");
    private static final Pattern TRAILING_QUOTES =
            Pattern.compile("[\"'“”‘’「」『』《》\\[\\]()（）]+$");

    /** 结尾标点 */
    private static final Pattern TRAILING_PUNCTUATION =
            Pattern.compile("[。．.！!？?，,；;、：:\\s]+$");

    /** 所有标点（兜底标题里连中间标点也去掉，标签不该有断句） */
    private static final Pattern ANY_PUNCTUATION =
            Pattern.compile("[。．.！!？?，,；;、：:…—\\-\\s]+");

    /** 中文之间的空白 */
    private static final Pattern CJK_GAP =
            Pattern.compile("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])");

    /**
     * 开头口语/请求词。只在"标题被写成了半句话"时兜底剥离，
     * 因此这里必须是完整词（"请帮"而不是"请"），否则会把"请求转发"削成"求转发"。
     */
    private static final Pattern LEADING_FILLER = Pattern.compile(
            "^(?:你好|您好|哈喽|嗨|在吗|帮我看看|帮我|帮忙|请帮|麻烦|我想问|我想|想问|请问|问一下|问下"
                    + "|能不能|可不可以|可以帮我|给我|老师让我|我需要|我想要|看看|看一下)[\\s，,、:：]*");

    /** 结尾语气词 */
    private static final Pattern TRAILING_MODAL = Pattern.compile("[吗呢吧啊呀嘛哦哈呗啦]+$");

    /**
     * 解释型输出的特征词：模型没压缩，而是把"我怎么想的"写出来了。
     * 命中即判为无效——宁可保留兜底标题，也不要一条像分析报告的标题。
     */
    private static final Pattern EXPLANATION_LEAK = Pattern.compile(
            "用户(想|希望|询问|要求|需要|意图|提问)|该(会话|消息)|本条消息|这条消息|这是(一个|一条|关于)"
                    + "|标题(应|可以|建议|为)|建议标题|概括为|可以命名为|综上");

    /** 兜底压缩时要去掉的疑问尾巴（"这个实验报告有什么问题" → "这个实验报告"） */
    private static final Pattern TRAILING_QUESTION = Pattern.compile(
            "(有什么|有哪些|是什么|怎么办|怎么样|怎么回事|好不好|该如何|怎么做).*$");

    /** 兜底压缩的分句符 */
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("[。．.！!？?；;\\n\\r]+");

    /** 兜底标题里每个分句至少要有这么长，否则继续拼下一句 */
    private static final int MIN_CLAUSE_LENGTH = 4;

    private TitleNormalizer() {
    }

    /**
     * 清洗模型输出。
     *
     * @param raw 模型原始输出
     * @return 可直接落库的标题；不可用时返回 {@code null}（调用方应重试或保留兜底标题）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // 1. 折叠空白：多行输出压成一行
        String title = raw.replaceAll("\\s+", " ").trim();

        // 1.1 去掉中文之间的空格（多行折叠的副产物），英文/数字间空格保留
        title = CJK_GAP.matcher(title).replaceAll("");

        // 2. 跑偏保护：正常标题不可能这么长
        if (title.length() > MAX_RAW_OUTPUT_LENGTH) {
            return null;
        }

        // 3. 去掉 Markdown 标记（# 标题、** 加粗、` 代码）
        title = LEADING_MARKDOWN.matcher(title).replaceFirst("");
        title = title.replace("*", "").replace("`", "").trim();

        // 4. 去掉 "标题：" / "会话标题:" 之类前缀
        title = TITLE_PREFIX.matcher(title).replaceFirst("").trim();

        // 5. 解释型输出直接判废：模型在写分析，不是在起标题
        if (EXPLANATION_LEAK.matcher(title).find()) {
            return null;
        }

        // 6. 去掉首尾包裹的引号/括号
        title = LEADING_QUOTES.matcher(title).replaceFirst("");
        title = TRAILING_QUOTES.matcher(title).replaceFirst("");
        title = title.trim();

        // 7. 去掉结尾标点
        title = TRAILING_PUNCTUATION.matcher(title).replaceFirst("").trim();

        // 8. 去掉口语开头与语气尾巴（"帮我看看…" / "…吗"），最多剥两轮
        for (int i = 0; i < 2; i++) {
            String before = title;
            title = LEADING_FILLER.matcher(title).replaceFirst("").trim();
            title = TRAILING_MODAL.matcher(title).replaceFirst("").trim();
            title = TRAILING_PUNCTUATION.matcher(title).replaceFirst("").trim();
            if (title.equals(before)) {
                break;
            }
        }

        if (title.length() < MIN_TITLE_LENGTH) {
            return null;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = truncateAtWordBoundary(title);
        }
        return title.isEmpty() ? null : title;
    }

    /**
     * 是否是"还没有真标题"的占位状态。
     *
     * <p>用于两处：① 模型只吐出"新对话"时不落库（保持 {@code title_source=AUTO}，
     * 下一轮还能再生成）；② 后续轮次判断要不要补生成标题。</p>
     */
    public static boolean isPlaceholder(String title) {
        return title == null || title.isBlank() || PLACEHOLDER.equals(title.trim());
    }

    /**
     * 本地兜底标题——模型没给出可用标题时，至少给列表一个像样的标签。
     *
     * <p>与"截前 30 字"的区别：先剥口语开头、按标点分句、去掉疑问尾巴，
     * 得到的是"软件工程导论学习"这种标签，而不是"你好我想学习软件工程导论的课程"这种半句话。</p>
     *
     * @param userMessage 已经过 {@link TitleInputExtractor} 清洗的用户消息
     * @return 永远非空；确实没有内容时返回 {@link #PLACEHOLDER}
     */
    public static String fallbackTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return PLACEHOLDER;
        }

        String text = userMessage.replaceAll("\\s+", " ").trim();

        // 1. 剥掉"你好 / 帮我 / 我想"这类开头，最多两轮（"你好，我想…"）
        for (int i = 0; i < 2; i++) {
            String before = text;
            text = LEADING_FILLER.matcher(text).replaceFirst("").trim();
            if (text.equals(before)) {
                break;
            }
        }

        // 2. 按句号问号等切成短句，取第一段够长的——列表标题不需要把整句塞进去
        String candidate = "";
        for (String clause : CLAUSE_SEPARATOR.split(text)) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            candidate = candidate.isEmpty() ? trimmed : candidate + trimmed;
            if (candidate.length() >= MIN_CLAUSE_LENGTH) {
                break;
            }
        }
        if (candidate.isEmpty()) {
            candidate = text;
        }

        // 3. 去掉疑问尾巴（"…有什么问题" → "…"）与所有标点，标签里不该有断句
        candidate = TRAILING_QUESTION.matcher(candidate).replaceFirst("").trim();
        candidate = ANY_PUNCTUATION.matcher(candidate).replaceAll(" ").trim();
        candidate = candidate.replaceAll("\\s+", " ");

        if (candidate.length() < MIN_TITLE_LENGTH) {
            return PLACEHOLDER;
        }
        return candidate.length() <= FALLBACK_TITLE_LENGTH
                ? candidate
                : candidate.substring(0, FALLBACK_TITLE_LENGTH).trim();
    }

    /**
     * 模型是否只是把用户原话复读了一遍（没有压缩）。
     *
     * <p>短消息复读无所谓（"软件工程导论学习"本来就该长这样），
     * 只拦长度 ≥ 12 的复读——那说明模型偷懒，直接抄了前 12 个字。</p>
     */
    public static boolean isEcho(String title, String userMessage) {
        if (title == null || userMessage == null || title.length() < 12) {
            return false;
        }
        String a = title.replaceAll("\\s+", "");
        String b = userMessage.replaceAll("\\s+", "");
        return b.startsWith(a);
    }

    /**
     * 截断到上限，但不要把一个英文单词切成两半（{@code TypeScri}）。
     * <p>中文按字切没关系，英文/数字连续串切一半就不可读了。</p>
     */
    private static String truncateAtWordBoundary(String title) {
        String cut = title.substring(0, MAX_TITLE_LENGTH).trim();
        if (cut.isEmpty()) {
            return cut;
        }
        char last = cut.charAt(cut.length() - 1);
        boolean insideWord = (last >= 'a' && last <= 'z') || (last >= 'A' && last <= 'Z')
                || (last >= '0' && last <= '9');
        if (!insideWord) {
            return cut;
        }
        int space = cut.lastIndexOf(' ');
        return space > MAX_TITLE_LENGTH / 2 ? cut.substring(0, space).trim() : cut;
    }
}
