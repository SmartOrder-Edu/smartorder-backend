package com.smartorder.edu.agent.model.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧客户端仍按 generationId 读取；新接口只写 taskId，返回体保留只读别名。
 */
class AgentTaskVOTest {

    @Test
    void shouldExposeTaskIdAndLegacyGenerationIdAlias() throws Exception {
        AgentTaskVO vo = AgentTaskVO.builder()
                .taskId("task-1")
                .conversationId("c-1")
                .assistantMessageId(12L)
                .status("RUNNING")
                .build();

        String json = new ObjectMapper().writeValueAsString(vo);

        assertTrue(json.contains("\"taskId\":\"task-1\""));
        assertTrue(json.contains("\"generationId\":\"task-1\""));
    }
}