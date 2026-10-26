package com.smartorder.edu.agent.config;

import com.smartorder.edu.agent.service.impl.ConversationCompressServiceImpl;
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
    void shouldUseBoundedProfileUpdateExecutor() {
        Executor executor = new StudentExecutorConfig().profileUpdateExecutor();

        ThreadPoolTaskExecutor taskExecutor =
                assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
        assertEquals(2, taskExecutor.getCorePoolSize());
        assertEquals(4, taskExecutor.getMaxPoolSize());
        taskExecutor.shutdown();
    }

    @Test
    void shouldKeepConversationCompressionAsync() throws Exception {
        // @Async 标注在实现类上（接口不承载调度注解），运行时由 CGLIB 代理读取实现类元数据
        Method method = ConversationCompressServiceImpl.class
                .getMethod("checkAndCompress", String.class, Long.class);

        Async async = method.getAnnotation(Async.class);

        assertEquals("agentAsyncExecutor", async.value());
    }
}
