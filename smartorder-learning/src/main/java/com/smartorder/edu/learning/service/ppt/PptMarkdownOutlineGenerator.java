package com.smartorder.edu.learning.service.ppt;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PptMarkdownOutlineGenerator {

    private final ObjectProvider<ChatModel> chatModelProvider;

    public String generate(PptOutlineContext context) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackMarkdown(context);
        }
        try {
            String content = sanitize(chatModel.chat(buildPrompt(context)));
            return content.isBlank() ? fallbackMarkdown(context) : content;
        } catch (Exception e) {
            log.warn("[PptOutline] LLM outline generation failed, fallback to template. topic={}",
                    context.topic(), e);
            return fallbackMarkdown(context);
        }
    }

    private String buildPrompt(PptOutlineContext context) {
        return """
                你是学习空间的 PPT 大纲 Agent。请根据资源内容生成可直接交给 Docmee 生成 PPT 的 Markdown 大纲。

                约束：
                - 只输出 Markdown，不要输出代码围栏、JSON 或额外解释。
                - 使用一级标题作为 PPT 标题。
                - 使用二级标题表示每一页幻灯片。
                - 每页使用 3 到 5 个要点，语言适合学生课堂学习。
                - 必须包含：学习目标、核心概念、例题或案例、易错点、总结复盘。
                - 目标页数为 %d 页左右。

                学习空间：%s
                原资源标题：%s
                PPT 主题：%s
                用户意图：%s

                原资源内容：
                %s
                """.formatted(
                targetSlides(context),
                value(context.spaceName()),
                value(context.sourceTitle()),
                value(context.topic()),
                value(context.userIntent()),
                abbreviate(context.sourceContent(), 5000)
        );
    }

    private String fallbackMarkdown(PptOutlineContext context) {
        String topic = value(context.topic());
        return """
                # %s

                ## 学习目标
                - 说清楚「%s」的核心定义
                - 理解它在当前学习任务中的适用场景
                - 能用一个例子完成分析和复盘

                ## 核心概念
                - 概念边界：先判断它解决什么问题
                - 关键条件：识别使用前提和限制
                - 输出结果：明确学习后应能完成的动作

                ## 例题或案例
                - 从一个典型问题开始
                - 标注题目中的关键条件
                - 按步骤推导结论

                ## 易错点
                - 只记结论，忽略适用条件
                - 混淆相似概念的边界
                - 没有用例题验证理解

                ## 总结复盘
                - 用一句话解释「%s」
                - 写下一个正例和一个反例
                - 标记下一步需要练习的问题
                """.formatted(topic, topic, topic);
    }

    private String sanitize(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fence = content.lastIndexOf("```");
            if (fence >= 0) {
                content = content.substring(0, fence).trim();
            }
        }
        return content;
    }

    private String abbreviate(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "无";
        }
        String trimmed = content.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private int targetSlides(PptOutlineContext context) {
        Integer slides = context.targetSlides();
        if (slides == null || slides <= 0) {
            return 8;
        }
        return Math.max(5, Math.min(20, slides));
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "未提供" : text.trim();
    }
}
