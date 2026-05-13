package com.smartorder.edu.agent.tool;

/**
 * 工具处理接口——所有业务工具必须实现此接口
 *
 * 不要用 Map<String,Object> 作为入参，每个工具定义自己的请求类型
 */
public interface ToolHandler {

    /** 工具唯一标识 */
    String name();

    /** 工具描述，用于 LLM 识别何时调用 */
    String description();

    /** 工具请求参数类型 */
    Class<?> requestType();

    /** 工具分类 */
    ToolCategory category();

    /** 执行工具，返回结果 */
    Object execute(Object request, ToolInvokeContext context);
}
