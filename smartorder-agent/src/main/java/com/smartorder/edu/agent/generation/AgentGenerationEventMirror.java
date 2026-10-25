package com.smartorder.edu.agent.generation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件归档的异步镜像。
 *
 * <p>为什么异步：一次生成会推送成百上千个 delta 事件，如果每个事件都同步等一次
 * 远程 Redis RTT（10~30ms），流式输出会被明显拖慢。这里用单线程 + 有界队列：
 * 追加顺序由队列保证，与 seq 顺序一致；生成本身只做一次 {@code offer}。</p>
 *
 * <p>终态时调用 {@link #flush(String)} 等待队列排空，再写终态元数据，
 * 保证“状态是 COMPLETED”时日志里一定已经有 done 事件。</p>
 *
 * <p>队列满或写入失败不会打断生成：标记该生成 replayDegraded，
 * 本机重连仍能从内存日志完整回放，跨进程回放则明确失败而不是静默丢事件。</p>
 */
@Slf4j
@Component
public class AgentGenerationEventMirror {

    /** 队列容量：按单事件约 0.5KB 估算，20 万条约 100MB 上限，实际远达不到 */
    private static final int QUEUE_CAPACITY = 200_000;

    private static final long FLUSH_TIMEOUT_SECONDS = 5;

    private final AgentGenerationEventStore store;

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentHashMap<String, Boolean> degradedMarked = new ConcurrentHashMap<>();

    private final Thread worker;

    private long dropped;

    public AgentGenerationEventMirror(AgentGenerationEventStore store) {
        this.store = store;
        this.worker = new Thread(this::loop, "generation-archive");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    public void start() {
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        worker.interrupt();
    }

    /**
     * 追加一条事件到归档队列（非阻塞）。
     */
    public void append(String generationId, AgentGenerationEvent event) {
        if (!running.get()) {
            return;
        }
        if (!queue.offer(Task.event(generationId, event))) {
            dropped++;
            if (dropped % 100 == 1) {
                log.error("[GenerationMirror] 归档队列已满，事件被丢弃: generationId={}, dropped={}",
                        generationId, dropped);
            }
            markDegraded(generationId);
        }
    }

    /**
     * 等待队列中此前入队的事件全部落库。终态屏障使用。
     */
    public void flush(String generationId) {
        if (!running.get()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (!queue.offer(Task.barrier(generationId, latch))) {
            log.warn("[GenerationMirror] 队列已满，跳过终态屏障: generationId={}", generationId);
            return;
        }
        try {
            if (!latch.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[GenerationMirror] 终态屏障等待超时: generationId={}", generationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        while (running.get() || !queue.isEmpty()) {
            Task task;
            try {
                task = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (task == null) {
                continue;
            }
            runTask(task);
            // 批量排空，减少线程调度开销
            Task next;
            while ((next = queue.poll()) != null) {
                runTask(next);
            }
        }
        log.info("[GenerationMirror] 归档线程退出，剩余任务={}", queue.size());
    }

    private void runTask(Task task) {
        if (task.barrier != null) {
            task.barrier.countDown();
            return;
        }
        try {
            store.appendEvent(task.generationId, task.event);
        } catch (Exception e) {
            markDegraded(task.generationId);
            if (degradedMarked.putIfAbsent(task.generationId, Boolean.TRUE) == null) {
                log.error("[GenerationMirror] 事件归档失败，该生成只能本机回放: generationId={}, seq={}",
                        task.generationId, task.event.getSeq(), e);
            }
        }
    }

    private void markDegraded(String generationId) {
        try {
            store.markReplayDegraded(generationId);
        } catch (Exception e) {
            log.debug("[GenerationMirror] 降级标记写入失败: {}", e.getMessage());
        }
    }

    /**
     * 队列任务：普通事件 or 屏蔽屏障。用一个类承载，避免额外对象分配。
     */
    private static final class Task {

        private final String generationId;

        private final AgentGenerationEvent event;

        private final CountDownLatch barrier;

        private Task(String generationId, AgentGenerationEvent event, CountDownLatch barrier) {
            this.generationId = generationId;
            this.event = event;
            this.barrier = barrier;
        }

        static Task event(String generationId, AgentGenerationEvent event) {
            return new Task(generationId, event, null);
        }

        static Task barrier(String generationId, CountDownLatch latch) {
            return new Task(generationId, null, latch);
        }
    }
}
