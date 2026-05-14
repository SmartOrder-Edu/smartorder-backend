package com.smartorder.edu.agent.tool;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Component;

/**
 * 工具结果截断器——避免过大的工具结果塞进 LLM 上下文或 SSE 推送
 */
@Component
public class ToolResultTruncator {

    private static final int DEFAULT_MAX_CHARS = 2000;

    /**
     * 截断结果到指定字符数
     */
    public Object truncate(Object result, int maxChars) {
        if (result == null) return null;

        String json = result instanceof String ? (String) result : JSON.toJSONString(result);

        if (json.length() <= maxChars) {
            return result;
        }

        String truncated = json.substring(0, maxChars) + "...[截断，原长度" + json.length() + "字符]";
        return truncated;
    }

    public Object truncate(Object result) {
        return truncate(result, DEFAULT_MAX_CHARS);
    }
}
