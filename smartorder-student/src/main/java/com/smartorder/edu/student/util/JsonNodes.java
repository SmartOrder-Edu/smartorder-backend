package com.smartorder.edu.student.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartorder.edu.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * JsonNode 读取工具：统一处理字段缺失、类型兼容与校验。
 *
 * <p>所有方法均为静态，不依赖 ObjectMapper，便于在任何上下文中使用。</p>
 *
 * @author 马强
 * @since 2026-07-12
 */
public final class JsonNodes {

    private JsonNodes() {}

    /** 读取文本字段，缺失或为空时返回默认值 */
    public static String readText(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? defaultValue : text;
    }

    /** 读取必填文本字段，缺失或为空时抛业务异常 */
    public static String readRequiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new BusinessException("LLM题目缺少必填字段: " + field);
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("LLM题目字段为空: " + field);
        }
        return text;
    }

    /** 读取整数字段，兼容数字与字符串，异常时返回默认值 */
    public static int readInt(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 读取布尔字段，兼容布尔与字符串 */
    public static boolean readBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText());
        }
        return defaultValue;
    }

    /** 读取双精度字段，兼容数字与字符串 */
    public static double readDouble(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 读取字符串数组字段（遍历取 asText，不依赖 ObjectMapper） */
    public static List<String> readStringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new BusinessException("字段 " + field + " 必须是JSON数组");
        }
        List<String> result = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            result.add(element.asText());
        }
        return result;
    }

    /** 判断节点是否包含指定字段且非 null */
    public static boolean hasField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }
}
