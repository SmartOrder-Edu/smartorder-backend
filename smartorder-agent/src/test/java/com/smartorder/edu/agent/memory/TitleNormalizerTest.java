package com.smartorder.edu.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleNormalizerTest {

    @Test
    @DisplayName("正常输出原样保留")
    void shouldKeepNormalTitle() {
        assertEquals("三天复习线性代数计划", TitleNormalizer.normalize("三天复习线性代数计划"));
    }

    @Test
    @DisplayName("去掉“标题：”前缀")
    void shouldStripTitlePrefix() {
        assertEquals("Python KeyError 排查", TitleNormalizer.normalize("标题：Python KeyError 排查"));
        assertEquals("考研数学一与数学二区别", TitleNormalizer.normalize("会话标题: 考研数学一与数学二区别"));
        assertEquals("实验报告问题分析", TitleNormalizer.normalize("标题 :实验报告问题分析"));
    }

    @Test
    @DisplayName("去掉首尾引号与括号")
    void shouldStripWrappingQuotes() {
        assertEquals("英文段落翻译", TitleNormalizer.normalize("「英文段落翻译」"));
        assertEquals("英文段落翻译", TitleNormalizer.normalize("\"英文段落翻译\""));
        assertEquals("英文段落翻译", TitleNormalizer.normalize("《英文段落翻译》"));
        assertEquals("英文段落翻译", TitleNormalizer.normalize("（英文段落翻译）"));
        assertEquals("英文段落翻译", TitleNormalizer.normalize("'英文段落翻译'"));
    }

    @Test
    @DisplayName("去掉 Markdown 标记")
    void shouldStripMarkdown() {
        assertEquals("三天复习计划", TitleNormalizer.normalize("## 三天复习计划"));
        assertEquals("三天复习计划", TitleNormalizer.normalize("**三天复习计划**"));
        assertEquals("三天复习计划", TitleNormalizer.normalize("> 三天复习计划"));
    }

    @Test
    @DisplayName("去掉结尾标点")
    void shouldStripTrailingPunctuation() {
        assertEquals("三天复习计划", TitleNormalizer.normalize("三天复习计划。"));
        assertEquals("三天复习计划", TitleNormalizer.normalize("三天复习计划？"));
        assertEquals("三天复习计划", TitleNormalizer.normalize("三天复习计划，"));
        assertEquals("三天复习计划", TitleNormalizer.normalize("三天复习计划."));
    }

    @Test
    @DisplayName("多行输出折叠成一行")
    void shouldCollapseMultilineOutput() {
        assertEquals("三天复习线性代数", TitleNormalizer.normalize("三天复习\n线性代数\n"));
    }

    @Test
    @DisplayName("英文单词间的空格必须保留，中文之间的空格要去掉")
    void shouldKeepLatinSpacesButDropCjkSpaces() {
        assertEquals("Hello World", TitleNormalizer.normalize("Hello World"));
        assertEquals("Python KeyError 排查", TitleNormalizer.normalize("Python KeyError 排查"));
        assertEquals("三天复习线性代数", TitleNormalizer.normalize("三天复习  线性代数"));
    }

    @Test
    @DisplayName("超长输出判为跑偏，返回 null 保留截断标题")
    void shouldRejectOverlongOutput() {
        String runaway = "根据用户的消息，我分析认为这应该是一个关于线性代数复习计划的请求，"
                + "用户希望在三天内完成第三章的复习，因此标题应该是三天复习线性代数第三章计划";
        assertNull(TitleNormalizer.normalize(runaway));
    }

    @Test
    @DisplayName("长度超出上限时截断到上限，且不切断英文单词")
    void shouldTruncateToMaxLength() {
        String longTitle = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        String result = TitleNormalizer.normalize(longTitle);
        assertTrue(result != null && result.length() <= TitleNormalizer.MAX_TITLE_LENGTH,
                "标题应被截断到 " + TitleNormalizer.MAX_TITLE_LENGTH + " 字符以内");

        // 中英混排到上限时，不能把 TypeScript 切成 TypeScri
        String mixed = "Claude Code用TypeScript开发插件并打包发布的完整流程";
        String cut = TitleNormalizer.normalize(mixed);
        assertTrue(cut != null && !cut.endsWith("TypeScri"), "英文单词被切断了: " + cut);
    }

    @Test
    @DisplayName("空值与无意义短输出返回 null")
    void shouldRejectBlankOrTooShort() {
        assertNull(TitleNormalizer.normalize(null));
        assertNull(TitleNormalizer.normalize(""));
        assertNull(TitleNormalizer.normalize("   "));
        assertNull(TitleNormalizer.normalize("好"));
        assertNull(TitleNormalizer.normalize("标题："));
        assertNull(TitleNormalizer.normalize("\"\""));
    }

    @Test
    @DisplayName("“新对话”是合法标题，不能被判为无效")
    void shouldAcceptFallbackTitle() {
        assertEquals("新对话", TitleNormalizer.normalize("新对话"));
        assertEquals("新对话", TitleNormalizer.normalize("新对话。"));
    }

    @Test
    @DisplayName("模型把标题写成半句话时，剥掉口语开头")
    void shouldStripLeadingFiller() {
        assertEquals("这个附件里的实验报告有什么问题", TitleNormalizer.normalize("帮我看看这个附件里的实验报告有什么问题"));
        assertEquals("学习软件工程导论", TitleNormalizer.normalize("我想学习软件工程导论"));
        assertEquals("Python KeyError 排查", TitleNormalizer.normalize("请问 Python KeyError 排查"));
    }

    @Test
    @DisplayName("剥掉结尾语气词：标签不该带“吗/呢/吧”")
    void shouldStripTrailingModalParticle() {
        assertEquals("Redis分布式锁tryLock参数", TitleNormalizer.normalize("Redis分布式锁tryLock参数呢"));
        assertEquals("线性代数三天复习", TitleNormalizer.normalize("线性代数三天复习吗？"));
    }

    @Test
    @DisplayName("“请求转发”这类正常词不能被当成口语前缀削掉")
    void shouldNotBreakWordStartingWithFillerPrefix() {
        assertEquals("请求转发链路", TitleNormalizer.normalize("请求转发链路"));
        assertEquals("软件工程导论学习", TitleNormalizer.normalize("软件工程导论学习"));
    }

    @Test
    @DisplayName("解释型输出判为无效，交给调用方重试")
    void shouldRejectExplanationStyleOutput() {
        assertNull(TitleNormalizer.normalize("用户希望学习软件工程导论"));
        assertNull(TitleNormalizer.normalize("这是一个学习软件工程导论的对话"));
        assertNull(TitleNormalizer.normalize("标题应为软件工程导论学习"));
    }

    @Test
    @DisplayName("占位标题识别：null/空/新对话 都算没有真标题")
    void shouldDetectPlaceholder() {
        assertTrue(TitleNormalizer.isPlaceholder(null));
        assertTrue(TitleNormalizer.isPlaceholder(" "));
        assertTrue(TitleNormalizer.isPlaceholder("新对话"));
        assertFalse(TitleNormalizer.isPlaceholder("软件工程导论学习"));
    }

    @Test
    @DisplayName("复读检测：只拦长度 ≥12 的照抄，短标题本来就该跟原话接近")
    void shouldDetectEcho() {
        String message = "帮我看看这个附件里的实验报告有什么问题";
        assertTrue(TitleNormalizer.isEcho("帮我看看这个附件里的实验报告", message));
        assertFalse(TitleNormalizer.isEcho("实验报告问题分析", message));
        assertFalse(TitleNormalizer.isEcho("软件工程导论学习", "软件工程导论学习"));
    }

    @Test
    @DisplayName("兜底标题：剥口语开头与标点，压成标签而不是半句话")
    void shouldBuildFallbackTitle() {
        assertEquals("学习软件工程导论", TitleNormalizer.fallbackTitle("你好，我想学习软件工程导论"));
        assertEquals("这个附件里的实验报告", TitleNormalizer.fallbackTitle("帮我看看这个附件里的实验报告有什么问题"));
        assertEquals("新对话", TitleNormalizer.fallbackTitle(""));
        assertEquals("新对话", TitleNormalizer.fallbackTitle(null));
    }

    @Test
    @DisplayName("兜底标题不超过列表可展示长度")
    void fallbackTitleShouldBeShort() {
        String longMessage = "软件工程专业大二学生，系统自学，学过Java语言，用一个月的时间来学，张海藩《软件工程导论》";
        String title = TitleNormalizer.fallbackTitle(longMessage);
        assertTrue(title.length() <= TitleNormalizer.FALLBACK_TITLE_LENGTH, "兜底标题过长: " + title);
        assertFalse(title.contains("，"), "兜底标题不该保留标点: " + title);
    }
}
