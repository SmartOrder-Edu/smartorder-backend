package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.AgentChatRequest;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** 旧聊天 SSE 仍可供兼容页面使用，但接口文档必须指向可恢复的新流程。 */
class LegacyAgentChatDeprecationTest {
    @Test
    void marksLegacyStreamAsDeprecatedAndNamesReplacementEndpoints() throws Exception {
        Method endpoint = AgentChatController.class.getMethod("chatStream", AgentChatRequest.class);

        Deprecated deprecated = endpoint.getAnnotation(Deprecated.class);
        assertNotNull(deprecated, "旧接口需要 Java 弃用标记");
        assertEquals("2.0", deprecated.since());
        assertFalse(deprecated.forRemoval(), "仍有兼容页面调用，暂不承诺删除");

        Operation operation = endpoint.getAnnotation(Operation.class);
        assertNotNull(operation);
        assertTrue(operation.deprecated(), "OpenAPI 中也应显示已过时");
        assertTrue(operation.description().contains("POST /agent/tasks"));
        assertTrue(operation.description().contains("GET /agent/tasks/{taskId}/events"));
    }
}
