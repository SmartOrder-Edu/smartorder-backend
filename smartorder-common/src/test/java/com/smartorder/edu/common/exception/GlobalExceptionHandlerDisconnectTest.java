package com.smartorder.edu.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.junit.jupiter.api.Assertions.*;

/** SSE 响应失效时不能交给返回 JSON 的通用异常处理方法。 */
class GlobalExceptionHandlerDisconnectTest {
    @Test
    void asyncDisconnectUsesVoidHandler() {
        var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        var method = resolver.resolveMethod(new AsyncRequestNotUsableException("client disconnected"));

        assertNotNull(method);
        assertEquals("handleAsyncRequestNotUsable", method.getName());
        assertEquals(void.class, method.getReturnType());
    }

    /** 模拟 SSE 响应发生异步写入错误，异常处理不能再触发 JSON 消息转换。 */
    @Test
    void brokenSseResponseDoesNotAttemptJsonConversion() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BrokenSseController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        var response = mvc.perform(get("/broken-sse").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();

        assertEquals("", response.getResponse().getContentAsString());
        assertInstanceOf(AsyncRequestNotUsableException.class, response.getResolvedException());
    }

    @RestController
    static class BrokenSseController {
        @GetMapping(value = "/broken-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter broken() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("client disconnected");
        }
    }
}
