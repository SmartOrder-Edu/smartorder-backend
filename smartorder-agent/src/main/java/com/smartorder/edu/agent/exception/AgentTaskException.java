package com.smartorder.edu.agent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 对话任务的协议异常——控制器据此返回真实的 HTTP 状态码。
 *
 * <p>为什么不走全局异常处理器：全局处理器统一返回 HTTP 200 + 业务码，
 * 而 SSE 订阅端需要在建立连接前就用状态码区分 401/403/404/410，
 * 否则浏览器拿到的是一段 JSON 而不是事件流。</p>
 */
@Getter
public class AgentTaskException extends RuntimeException {

    private final HttpStatus status;

    private final String code;

    public AgentTaskException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AgentTaskException notFound(String taskId) {
        return new AgentTaskException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND",
                "对话任务不存在或已过期: " + taskId);
    }

    public static AgentTaskException forbidden() {
        return new AgentTaskException(HttpStatus.FORBIDDEN, "TASK_FORBIDDEN",
                "无权访问该对话任务");
    }

    public static AgentTaskException invalidCursor(long after) {
        return new AgentTaskException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "游标非法: after=" + after);
    }

    /**
     * 任务行已经预留，但执行线程或消息绑定还没完成：客户端应短暂退避后重试，而不是把任务标成中断。
     */
    public static AgentTaskException starting(String taskId) {
        return new AgentTaskException(HttpStatus.SERVICE_UNAVAILABLE, "TASK_STARTING",
                "任务正在启动，请稍后重试: " + taskId);
    }

    public static AgentTaskException cursorExpired(long after) {
        return new AgentTaskException(HttpStatus.GONE, "CURSOR_EXPIRED",
                "游标之前的事件已过期或被裁剪，无法保证完整回放: after=" + after);
    }
}
