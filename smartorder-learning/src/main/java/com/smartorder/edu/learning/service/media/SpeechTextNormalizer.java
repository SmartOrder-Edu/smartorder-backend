package com.smartorder.edu.learning.service.media;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SpeechTextNormalizer {

    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_SYMBOL = Pattern.compile("[#*_>`]");
    private static final Pattern MERMAID_LINE = Pattern.compile("(?m)^\\s*(flowchart|graph|sequenceDiagram|classDiagram)\\b.*$");

    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value
                .replace('\t', ' ')
                .replace("\r", "\n");
        text = FENCED_CODE.matcher(text).replaceAll(" 这里有一段示意图或代码，先关注其中的关键关系。 ");
        text = MERMAID_LINE.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = MARKDOWN_SYMBOL.matcher(text).replaceAll(" ");
        text = text
                .replace("=>", "推出")
                .replace("->", "指向")
                .replace("=", " 等于 ")
                .replace("+", " 加 ")
                .replace("-", " 减 ")
                .replace("*", " 乘 ")
                .replace("/", " 除以 ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
