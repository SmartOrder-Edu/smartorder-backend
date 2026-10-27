package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.runtime.store.InMemoryAgentTaskStateStore;
import com.smartorder.edu.agent.service.AgentTaskMetaService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 归档镜像的停机语义：停止时必须把队列里已有的事件写完，不能直接丢掉。
 */
class AgentTaskEventMirrorTest {

    @Test
    void shouldDrainQueuedEventsOnShutdown() {
        AgentTaskEventStore store = mock(AgentTaskEventStore.class);
        AgentTaskEventMirror mirror = new AgentTaskEventMirror(store,
                mock(AgentTaskMetaService.class), new InMemoryAgentTaskStateStore());
        mirror.start();

        for (int seq = 1; seq <= 50; seq++) {
            mirror.append("gen-1", AgentTaskEvent.builder()
                    .seq(seq)
                    .eventName("agent.text.delta")
                    .data("{}")
                    .build());
        }

        // stop() 会先等 worker 排空队列再退出，返回后事件必须已经全部落归档
        mirror.stop();

        verify(store, times(50)).appendEvent(eq("gen-1"), any(AgentTaskEvent.class));
    }
}
