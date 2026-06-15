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

    /** 工具风险等级，默认取分类的风险等级 */
    default ToolCategory.RiskLevel riskLevel() {
        return category().getRiskLevel();
    }

    /** 执行工具，返回结果 */
    Object execute(Object request, ToolInvokeContext context);

    /** 最大重试次数（0=不重试，适用于调用外部服务的工具如 WebSearchTool） */
    default int maxRetries() { return 0; }

    /** 重试间隔基数（毫秒），实际使用指数退避: delay * 2^(attempt-1)，上限 10s */
    default long retryDelayMs() { return 1000L; }
}
