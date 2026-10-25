package com.smartorder.edu.agent.generation;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证合并文本不会跨工具事件重排，也不会丢失尾部文本。 */
class AgentGenerationBufferTest {
    @Test
    void flushesTextBeforeToolAndTerminal() {
        List<String> output = new ArrayList<>();
        var buffer = new AgentGenerationBuffer((name, data) -> output.add(name + ":" + data));
        buffer.accept("agent.text.delta", "{\"payload\":{\"text\":\"你\"},\"traceId\":\"t\"}");
        buffer.accept("agent.text.delta", "{\"payload\":{\"text\":\"好\"},\"traceId\":\"t\"}");
        assertTrue(output.isEmpty());
        buffer.accept("agent.tool.started", "{}");
        buffer.accept("done", "{}");
        assertEquals(3, output.size());
        assertEquals("你好", JSON.parseObject(output.getFirst().substring("agent.text.delta:".length()))
                .getJSONObject("payload").getString("text"));
        assertTrue(output.get(1).startsWith("agent.tool.started:"));
        assertTrue(output.get(2).startsWith("done:"));
    }

    @Test
    void doesNotMergeDifferentReasoningSegments() {
        List<String> output = new ArrayList<>();
        var buffer = new AgentGenerationBuffer((name, data) -> output.add(data));
        buffer.accept("agent.think.delta", "{\"payload\":{\"text\":\"a\"},\"traceId\":\"one\"}");
        buffer.accept("agent.think.delta", "{\"payload\":{\"text\":\"b\"},\"traceId\":\"two\"}");
        buffer.flush();
        assertEquals(2, output.size());
    }
}
