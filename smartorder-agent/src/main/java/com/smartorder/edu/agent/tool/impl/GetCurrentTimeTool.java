package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 获取当前时间工具
 *
 * @author 谢光湘
 */
@Slf4j
@Component
public class GetCurrentTimeTool implements ToolHandler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DEFAULT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)", Locale.CHINESE);

    @Override
    public String name() {
        return "getCurrentTime";
    }

    @Override
    public String description() {
        return "获取当前日期和时间，返回格式如\"2026年06月21日 15:30:45 (星期六)\"。"
                + "当用户询问\"现在几点\"\"今天几号\"\"今天星期几\"等时间相关问题，"
                + "或需要基于当前时间进行推理时调用此工具";
    }

    @Override
    public Class<?> requestType() {
        return GetCurrentTimeRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.UTILITY;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String formatted = DEFAULT_FORMATTER.format(now);
        log.info("[GetCurrentTime] 返回当前时间: {}", formatted);
        return ToolResult.success(formatted);
    }

    @Data
    public static class GetCurrentTimeRequest {
        // 可选：自定义格式字符串。为空则使用默认格式
        private String format;
    }
}
