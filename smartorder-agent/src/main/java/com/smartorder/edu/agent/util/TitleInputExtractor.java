package com.smartorder.edu.agent.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话标题的输入清洗器。
 *
 * <p>用户首条消息并不总是"干净的问题"，直接丢给模型会把标题带偏：</p>
 * <ul>
 *   <li><b>系统注入块</b>：前端把 {@code [资源上下文]} 拼在消息最前面，
 *       里面含资源标题、正文摘要、学习目标。模型容易把"资源标题"或摘要里的
 *       小标题当成会话标题（如把"排序算法实现与比较"当成用户意图）。
 *       真正的用户问题在 {@code [用户问题]} 标记之后。</li>
 *   <li><b>Markdown / 代码块</b>：粘贴的长文与代码会把主题淹没。</li>
 *   <li><b>超长消息</b>：标题只需要开头一小段，没必要整篇送进模型。</li>
 * </ul>
 *
 * <p>纯函数，无状态。输出永远是"可以直接当用户问题读"的纯文本，可能为空串。</p>
 */
public final class TitleInputExtractor {

    /** 送进模型的最大长度：首条消息可能上万字，标题只需要开头部分 */
    public static final int MAX_INPUT_LENGTH = 2000;

    /** 前端注入的用户问题标记 */
    static final String USER_QUESTION_MARK = "[用户问题]";

    /** 前端注入的资源上下文标记 */
    static final String RESOURCE_MARK = "[资源上下文]";

    /** 资源上下文块：从标记到 [用户问题] 之前（或到结尾） */
    private static final Pattern RESOURCE_BLOCK =
            Pattern.compile("(?s)\\[资源上下文\\].*?(?=\\[用户问题\\]|$)");

    /** 资源上下文里的"标题：xxx"行——用户没写问题时，这是唯一可用的主题线索 */
    private static final Pattern RESOURCE_TITLE =
            Pattern.compile("标题\\s*[:：]\\s*([^\\r\\n]+)");

    /** 围栏代码块 */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");

    /** 行首 Markdown 标记：标题、列表、引用 */
    private static final Pattern LINE_MARK =
            Pattern.compile("(?m)^\\s{0,3}(?:#{1,6}\\s*|[-*+]\\s+|>\\s*|\\d+[.)]\\s*)");

    private TitleInputExtractor() {
    }

    /**
     * 从原始用户消息里提取"用户真正问的那段话"。
     *
     * @param rawMessage 原始首条消息
     * @return 清洗后的文本；没有任何可用内容时返回空串
     */
    public static String extract(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "";
        }

        String text = rawMessage;

        // 1. 有 [用户问题] 标记：用户真正的问题在标记之后，前面全是注入块
        int questionIdx = text.lastIndexOf(USER_QUESTION_MARK);
        if (questionIdx >= 0) {
            text = text.substring(questionIdx + USER_QUESTION_MARK.length());
        } else if (text.contains(RESOURCE_MARK)) {
            // 2. 只有资源上下文、没有用户问题：剥掉注入块，块里的"标题：xxx"留作弱线索
            String resourceTitle = firstResourceTitle(text);
            text = RESOURCE_BLOCK.matcher(text).replaceAll(" ");
            if (text.isBlank()) {
                return clean(resourceTitle);
            }
        }

        // 3. 围栏代码块：用户问"解释这段代码"时，代码本身对起标题没帮助；
        //    但整条消息就是一段代码时不能删，否则什么都不剩
        String withoutCode = FENCED_CODE.matcher(text).replaceAll(" ");
        if (!withoutCode.isBlank()) {
            text = withoutCode;
        }

        return clean(text);
    }

    // ============ Private ============

    private static String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = LINE_MARK.matcher(text).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= MAX_INPUT_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_INPUT_LENGTH).trim();
    }

    private static String firstResourceTitle(String text) {
        Matcher m = RESOURCE_TITLE.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
