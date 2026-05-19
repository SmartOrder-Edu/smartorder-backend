package com.smartorder.edu.common.rag;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Markdown 标准化服务。
 * 已接入 LangChain4j 与外部化提示词模板。
 */
@Service
public class MarkdownNormalizeService {

    private static final int MAX_PART_LENGTH = 6000;

    // 注入我们刚才配置的 Bean
    private final ChatModel chatLanguageModel;
    private final String markdownNormalizePromptTemplate;

    public MarkdownNormalizeService(ChatModel chatLanguageModel,
                                    @Qualifier("markdownNormalizePromptTemplate") String markdownNormalizePromptTemplate) {
        this.chatLanguageModel = chatLanguageModel;
        this.markdownNormalizePromptTemplate = markdownNormalizePromptTemplate;
    }

    public String toMarkdown(String rawText, String fileType, String fileName) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return "# " + fileName + "\n\n暂无有效文本。";
        }
        if (isMarkdownFile(fileType, fileName)) {
            return normalizeExistingMarkdown(rawText, fileName);
        }
        List<String> parts = splitByLength(rawText, MAX_PART_LENGTH);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < parts.size(); i++) {
            // 1. 构建带有变量的 Prompt
            String finalPrompt = buildPrompt(parts.get(i), fileType, fileName, i + 1, parts.size());

            // 2. 调用大模型
            String md = callLlm(finalPrompt);

            // 3. 清理与拼接
            sb.append("\n\n").append(cleanMarkdown(md));
        }
        return cleanMarkdown(sb.toString());
    }

    private String callLlm(String promptText) {
        try {
            // 真实的大模型调用
            return chatLanguageModel.chat(promptText);
        } catch (Exception e) {
            // 如果调用失败（如网络抖动、Token耗尽），进行安全降级处理
            e.printStackTrace();
            return fallbackMarkdown(promptText, "文档内容", "text");
        }
    }

    private String buildPrompt(String text, String fileType, String fileName, int partNo, int totalParts) {
        // 使用 LangChain4j 的 PromptTemplate 替换变量
        PromptTemplate template = PromptTemplate.from(markdownNormalizePromptTemplate);

        Map<String, Object> variables = new HashMap<>();
        variables.put("fileName", fileName);
        variables.put("fileType", fileType);
        variables.put("partNo", partNo);
        variables.put("totalParts", totalParts);
        variables.put("text", text);

        Prompt prompt = template.apply(variables);
        return prompt.text();
    }

    private String fallbackMarkdown(String rawText, String fileName, String fileType) {
        String title = fileName == null || fileName.isBlank() ? "文档内容" : fileName;
        // 如果是从 prompt 中降级，尝试提取真实文本部分以避免将 prompt 指令暴露给用户
        String marker = "原始文本：";
        int index = rawText.indexOf(marker);
        String cleanText = index >= 0 ? rawText.substring(index + marker.length()).trim() : rawText;

        String clean = cleanText.replaceAll("\\r\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (clean.matches("(?s)^#{1,6}\\s+.*")) {
            return clean;
        }
        return "# " + title + "\n\n## 解析内容\n\n" + clean;
    }

    private List<String> splitByLength(String text, int maxLength) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        for (int i = 0; i < text.length(); i += maxLength) {
            result.add(text.substring(i, Math.min(i + maxLength, text.length())));
        }
        return result;
    }

    private String cleanMarkdown(String markdown) {
        if (markdown == null) return "";
        return markdown
                .replaceAll("(?i)```markdown", "")
                .replaceAll("```", "")
                .trim();
    }

    private boolean hasMarkdownHeading(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 检查是否有以 # 开头的行（Markdown 标题）
        return text.lines().anyMatch(line -> line.trim().startsWith("#"));
    }

    private String safeTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "文档内容";
        }
        // 移除文件扩展名，作为标题
        String title = fileName;
        int lastDot = title.lastIndexOf('.');
        if (lastDot > 0) {
            title = title.substring(0, lastDot);
        }
        // 清理特殊字符，保留中文、字母、数字、空格、连字符
        title = title.replaceAll("[^\\w\\s\\u4e00-\\u9fa5-]", "").trim();
        if (title.isEmpty()) {
            return "文档内容";
        }
        return title;
    }
    private boolean isMarkdownFile(String fileType, String fileName) {
        if (fileType != null && fileType.toLowerCase().contains("markdown")) {
            return true;
        }

        if (fileName == null) {
            return false;
        }

        String lower = fileName.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private String normalizeExistingMarkdown(String markdown, String fileName) {
        String text = cleanMarkdown(markdown);

        if (!hasMarkdownHeading(text)) {
            text = "# " + safeTitle(fileName) + "\n\n## 文档内容\n\n" + text;
        }

        return text;
    }
}