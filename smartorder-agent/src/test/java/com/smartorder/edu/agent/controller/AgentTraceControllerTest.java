package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.vo.AgentTraceEventBriefVO;
import com.smartorder.edu.agent.service.AgentTraceQueryService;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTraceControllerTest {

    private final AgentTraceEventMapper mapper = mock(AgentTraceEventMapper.class);

    private final AgentTraceQueryService service = mock(AgentTraceQueryService.class);

    private final AgentTraceController controller = new AgentTraceController(mapper, service);

    @AfterEach
    void tearDown() {
        ThreadLocalUtils.remove();
    }

    @Test
    void shouldNormalizeIdsBeforeQuery() {
        login(7L);
        when(service.query(List.of("t1", "t2"), 7L)).thenReturn(Map.of(
                "t1", List.of(AgentTraceEventBriefVO.builder()
                        .traceId("t1").eventType("done").seq(1).payload("{}").build()),
                "t2", List.of()
        ));

        Result<Map<String, List<AgentTraceEventBriefVO>>> result =
                controller.getTraceEventsBatch(Arrays.asList(" t1 ", "", "t2", "t1", null));

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().get("t1").size());
        verify(service).query(List.of("t1", "t2"), 7L);
    }

    @Test
    void shouldRejectMoreThanFiftyTraceIdsBeforeQuerying() {
        login(7L);
        List<String> ids = IntStream.range(0, 51).mapToObj(i -> "t" + i).toList();

        Result<Map<String, List<AgentTraceEventBriefVO>>> result = controller.getTraceEventsBatch(ids);

        assertEquals(ResultCode.VALIDATION_ERROR.getCode(), result.getCode());
        assertTrue(result.getMsg().contains("50"));
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectUnauthenticatedRequest() {
        Result<Map<String, List<AgentTraceEventBriefVO>>> result =
                controller.getTraceEventsBatch(List.of("t1"));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), result.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void shouldBindCommaSeparatedTraceIdsOverHttp() throws Exception {
        login(7L);
        when(service.query(List.of("t1", "t2"), 7L)).thenReturn(Map.of(
                "t1", List.of(AgentTraceEventBriefVO.builder()
                        .traceId("t1").eventType("done").seq(2).payload("{}").build()),
                "t2", List.of()
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/agent/traces")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                        .param("traceIds", "t1,t2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.t1[0].eventType").value("done"))
                .andExpect(jsonPath("$.data.t1[0].seq").value(2));

        verify(service).query(List.of("t1", "t2"), 7L);
    }

    private void login(long userId) {
        Claims claims = mock(Claims.class);
        when(claims.get("userId", Long.class)).thenReturn(userId);
        ThreadLocalUtils.set(claims);
    }
}
