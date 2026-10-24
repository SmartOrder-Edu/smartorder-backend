package com.smartorder.edu.agent.stream;

import com.smartorder.edu.agent.config.AgentExecutorConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 任务线程池满载时必须快速拒绝，不能在提交请求线程运行 Agent。 */
class AgentTaskExecutorTest {
    @Test
    void saturatedExecutorNeverRunsAgentOnCallerThread() throws Exception {
        ExecutorService executor = new AgentExecutorConfig().agentTaskExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 填满所有工作线程及等待队列，再从当前线程提交一个任务。
            for (int i = 0; i < 116; i++) {
                executor.execute(() -> {
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
            long started = System.nanoTime();
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
                throw new AssertionError("Agent 不得在 HTTP 线程执行");
            }));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
