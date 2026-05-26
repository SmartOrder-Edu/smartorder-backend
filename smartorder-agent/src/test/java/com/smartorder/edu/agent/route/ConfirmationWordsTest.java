package com.smartorder.edu.agent.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确认词工具类单元测试
 */
class ConfirmationWordsTest {

    @Test
    void shouldReturnTrueForExactConfirmationWords() {
        assertTrue(ConfirmationWords.isConfirmationOnly("好的"));
        assertTrue(ConfirmationWords.isConfirmationOnly("可以"));
        assertTrue(ConfirmationWords.isConfirmationOnly("ok"));
        assertTrue(ConfirmationWords.isConfirmationOnly("OK"));
        assertTrue(ConfirmationWords.isConfirmationOnly("确认"));
        assertTrue(ConfirmationWords.isConfirmationOnly("创建吧"));
        assertTrue(ConfirmationWords.isConfirmationOnly("没问题"));
    }

    @Test
    void shouldReturnFalseForLongMessages() {
        assertFalse(ConfirmationWords.isConfirmationOnly("好的，我确认创建这个计划"));
        assertFalse(ConfirmationWords.isConfirmationOnly("可以 请你帮我安排"));
    }

    @Test
    void shouldReturnFalseForNonConfirmationWords() {
        assertFalse(ConfirmationWords.isConfirmationOnly("你好"));
        assertFalse(ConfirmationWords.isConfirmationOnly("帮我制定计划"));
        assertFalse(ConfirmationWords.isConfirmationOnly("学习"));
    }

    @Test
    void shouldReturnFalseForNullAndBlank() {
        assertFalse(ConfirmationWords.isConfirmationOnly(null));
        assertFalse(ConfirmationWords.isConfirmationOnly(""));
        assertFalse(ConfirmationWords.isConfirmationOnly("   "));
    }

    @Test
    void containsShouldMatchExact() {
        assertTrue(ConfirmationWords.contains("好的"));
        assertTrue(ConfirmationWords.contains("ok"));
        assertFalse(ConfirmationWords.contains("不好"));
        assertFalse(ConfirmationWords.contains(""));
    }
}
