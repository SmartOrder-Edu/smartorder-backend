package com.smartorder.edu.agent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 生成任务的协议异常——控制器据此返回真实的 HTTP 状态码。
 *
 * <p>为什么不走全局异常处理器：全局处理器统一返回 HTTP 200 + 业务码，
 * 而 SSE 订阅端需要在建立连接前就用状态码区分 401/403/404/410，
 * 否则浏览器拿到的是一段 JSON 而不是事件流。</p>
 */
@Getter
public class AgentGenerationException extends RuntimeException {

    private final HttpStatus status;

    private final String code;

    public AgentGenerationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AgentGenerationException notFound(String generationId) {
        return new AgentGenerationException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND",
                "生成任务不存在或已过期: " + generationId);
    }

    public static AgentGenerationException forbidden() {
        return new AgentGenerationException(HttpStatus.FORBIDDEN, "GENERATION_FORBIDDEN",
                "无权访问该生成任务");
    }

    public static AgentGenerationException invalidCursor(long after) {
        return new AgentGenerationException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "游标非法: after=" + after);
    }

    public static AgentGenerationException cursorExpired(long after) {
        return new AgentGenerationException(HttpStatus.GONE, "CURSOR_EXPIRED",
                "游标之前的事件已过期或被裁剪，无法保证完整回放: after=" + after);
    }
}
