package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.common.model.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent 协议异常 → 真实 HTTP 状态码。
 *
 * <p>为什么不交给全局 {@code GlobalExceptionHandler}：全局处理器把所有异常压成
 * HTTP 200 + 业务码，客户端无法区分 400/403/404/409。发送接口的“草稿冲突 /
 * 消息内容冲突”必须让前端拿到 409，而不是一句笼统的“系统异常”。</p>
 *
 * <p>只处理 {@link AgentTaskException}，并置于最高优先级，确保先于全局
 * {@code Exception} 处理器命中；其他异常仍走全局处理。</p>
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTaskExceptionHandler {

    @ExceptionHandler(AgentTaskException.class)
    public ResponseEntity<Result<Void>> handle(AgentTaskException e) {
        log.info("[AgentApi] 请求被拒绝: status={}, code={}, message={}",
                e.getStatus().value(), e.getCode(), e.getMessage());
        Result<Void> body = new Result<>(e.getStatus().value(), e.getMessage(), null);
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
