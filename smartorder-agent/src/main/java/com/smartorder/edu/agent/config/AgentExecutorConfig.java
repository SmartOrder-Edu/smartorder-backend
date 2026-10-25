package com.smartorder.edu.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 异步执行配置
 * <p>
 * 所有 Agent 相关线程池均在此配置，统一管理资源。
 * 替换各控制器中的 newCachedThreadPool，提供背压和快速失败能力。
 */
@Configuration
@EnableAsync
public class AgentExecutorConfig {

    @Bean("agentAsyncExecutor")
    public Executor agentAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-async-");
        executor.initialize();
        return executor;
    }

    /**
     * Agent Chat SSE 请求执行线程池
     * <p>
     * 用于 AgentRunSupervisor 执行引擎逻辑，与 SSE servlet 线程隔离。
     * CallerRunsPolicy 提供自然背压：队列满时由调用线程（servlet）同步执行，
     * 避免无限制创建线程。
     */
    @Bean("agentChatExecutor")
    public ExecutorService agentChatExecutor() {
        return new ThreadPoolExecutor(
                4,     // corePoolSize
                16,    // maxPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "agent-chat-" + System.nanoTime()); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Agent 生成任务执行线程池
     * <p>
     * 生成与连接已解耦：一次生成的整个生命周期都在这个线程池里跑，
     * 不占用 servlet 线程，也不随 SSE 连接断开而结束。
     * 使用 AbortPolicy 快速失败——创建接口需要立刻返回 JSON，
     * 不能被 CallerRunsPolicy 拖成同步执行。
     */
    @Bean("agentGenerationExecutor")
    public ExecutorService agentGenerationExecutor() {
        return new ThreadPoolExecutor(
                4,     // corePoolSize
                16,    // maxPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "agent-generation-" + System.nanoTime()); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Agent Plan 确认执行线程池
     * <p>
     * Plan 确认请求并发度低于 Chat，配置更保守。
     */
    @Bean("agentPlanExecutor")
    public ExecutorService agentPlanExecutor() {
        return new ThreadPoolExecutor(
                2,     // corePoolSize
                8,     // maxPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> { Thread t = new Thread(r, "agent-plan-" + System.nanoTime()); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 工具执行线程池
     * <p>
     * 用于 TracingToolExecutor 的超时执行。
     * 队列较大以容忍工具执行阻塞，但仍有上限。
     */
    @Bean("toolExecutionExecutor")
    public ExecutorService toolExecutionExecutor() {
        return new ThreadPoolExecutor(
                2,     // corePoolSize
                8,     // maxPoolSize
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> { Thread t = new Thread(r, "tool-exec-" + System.nanoTime()); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
