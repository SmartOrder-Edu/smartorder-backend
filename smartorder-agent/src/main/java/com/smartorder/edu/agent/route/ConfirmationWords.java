package com.smartorder.edu.agent.route;

import java.util.Set;

/**
 * 确认词工具类——统一管理用户确认短句，消除多处重复定义
 */
public final class ConfirmationWords {

    private static final Set<String> WORDS = Set.of(
            "好的", "可以", "是的", "行", "确认", "需要", "嗯", "对", "好",
            "创建吧", "开始吧", "没问题", "ok", "yes", "OK", "Yes",
            "搞吧", "做吧", "弄吧"
    );

    private ConfirmationWords() {
    }

    /**
     * 判断给定词是否在确认词列表中
     */
    public static boolean contains(String word) {
        return WORDS.contains(word);
    }

    /**
     * 判断消息是否为纯确认短句（<=10 字符且精确匹配确认词列表）
     */
    public static boolean isConfirmationOnly(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        return trimmed.length() <= 10 && WORDS.contains(trimmed);
    }
}
