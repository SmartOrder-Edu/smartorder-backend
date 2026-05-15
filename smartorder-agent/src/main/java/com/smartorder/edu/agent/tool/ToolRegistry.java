package com.smartorder.edu.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心——基于 ConcurrentHashMap 的自注册模式
 * 所有实现 ToolHandler 的 Bean 会自动注册
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolHandler> toolMap = new ConcurrentHashMap<>();

    public ToolRegistry(List<ToolHandler> handlers) {
        for (ToolHandler handler : handlers) {
            register(handler);
        }
        log.info("[ToolRegistry] 已注册 {} 个工具: {}", toolMap.size(), toolMap.keySet());
    }

    public void register(ToolHandler handler) {
        if (toolMap.containsKey(handler.name())) {
            throw new IllegalStateException("工具重复注册：" + handler.name());
        }
        toolMap.put(handler.name(), handler);
    }

    public ToolHandler get(String name) {
        ToolHandler handler = toolMap.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("工具不存在：" + name);
        }
        return handler;
    }

    public List<ToolHandler> getByNames(List<String> names) {
        return names.stream().map(this::get).toList();
    }

    public Collection<ToolHandler> getAll() {
        return toolMap.values();
    }

    public boolean contains(String name) {
        return toolMap.containsKey(name);
    }
}
