package com.smartorder.edu.agent.config;

import com.smartorder.edu.student.config.StudentExecutorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentExecutorConfigTest {

    @Test
    void shouldSerializeProfileUpdatesToAvoidLostReadModifyWriteChanges() {
        Executor executor = new StudentExecutorConfig().profileUpdateExecutor();

        ThreadPoolTaskExecutor taskExecutor =
                assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
        assertEquals(1, taskExecutor.getMaxPoolSize());
        taskExecutor.shutdown();
    }

    @Test
    void shouldKeepConversationCompressionOnTheGeneralAgentExecutor() throws Exception {
        Method method = com.smartorder.edu.agent.memory.ConversationCompressService.class
                .getMethod("checkAndCompress", String.class, Long.class);

        Async async = method.getAnnotation(Async.class);

        assertEquals("agentAsyncExecutor", async.value());
    }
}
