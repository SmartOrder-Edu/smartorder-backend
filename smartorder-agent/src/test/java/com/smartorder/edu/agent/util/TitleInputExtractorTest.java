package com.smartorder.edu.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleInputExtractorTest {

    @Test
    @DisplayName("普通消息原样保留")
    void shouldKeepPlainMessage() {
        assertEquals("你好，我想学习软件工程导论",
                TitleInputExtractor.extract("你好，我想学习软件工程导论"));
    }

    @Test
    @DisplayName("空消息返回空串")
    void shouldReturnEmptyForBlank() {
        assertEquals("", TitleInputExtractor.extract(null));
        assertEquals("", TitleInputExtractor.extract(""));
        assertEquals("", TitleInputExtractor.extract("   \n  "));
    }

    @Test
    @DisplayName("[资源上下文] 注入块被剥掉，只留 [用户问题] 之后的内容")
    void shouldStripResourceContextAndKeepUserQuestion() {
        String raw = """
                [资源上下文]
                标题：排序算法实现与比较
                类型：doc
                内容摘要：# 排序算法实现与比较
                ## 学习目标
                - 说清楚「算法与应用」的核心概念

                [用户问题]
                快速排序的最坏时间复杂度是多少
                """;

        String extracted = TitleInputExtractor.extract(raw);
        assertEquals("快速排序的最坏时间复杂度是多少", extracted);
        assertFalse(extracted.contains("资源上下文"), "注入块没剥干净: " + extracted);
        assertFalse(extracted.contains("学习目标"), "摘要正文混进来了: " + extracted);
    }

    @Test
    @DisplayName("只有资源上下文、没有用户问题：退回用块里的资源标题")
    void shouldFallbackToResourceTitle() {
        String raw = """
                [资源上下文]
                标题：讲义文档：数组
                类型：doc
                知识点：数组
                内容摘要：# 讲义文档：数组
                ## 学习目标
                - 说清楚「数组」的核心概念
                """;

        assertEquals("讲义文档：数组", TitleInputExtractor.extract(raw));
    }

    @Test
    @DisplayName("去掉行首 Markdown 标记，压成一行")
    void shouldStripMarkdownMarks() {
        String raw = """
                ## 帮我规划一下
                - 第一周学语法
                - 第二周写项目
                """;
        String extracted = TitleInputExtractor.extract(raw);
        assertFalse(extracted.contains("#"), "标题标记没去掉: " + extracted);
        assertFalse(extracted.contains("\n"), "没有压成一行: " + extracted);
        assertTrue(extracted.startsWith("帮我规划一下"), "正文被破坏: " + extracted);
    }

    @Test
    @DisplayName("有正文时剥掉代码块；整条消息就是代码时保留")
    void shouldDropCodeOnlyWhenThereIsText() {
        String withText = "解释这段代码\n```java\nint a = 1;\n```";
        assertEquals("解释这段代码", TitleInputExtractor.extract(withText));

        String codeOnly = "```java\nint a = 1;\n```";
        assertTrue(TitleInputExtractor.extract(codeOnly).contains("int a = 1"),
                "整条都是代码时不能删空");
    }

    @Test
    @DisplayName("超长消息截断到上限，避免把整篇文档送进模型")
    void shouldTruncateLongInput() {
        String raw = "学".repeat(TitleInputExtractor.MAX_INPUT_LENGTH + 500);
        assertEquals(TitleInputExtractor.MAX_INPUT_LENGTH,
                TitleInputExtractor.extract(raw).length());
    }
}
