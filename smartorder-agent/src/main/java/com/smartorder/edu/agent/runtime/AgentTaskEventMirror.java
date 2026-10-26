package com.smartorder.edu.agent.runtime;

import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.service.AgentTaskMetaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
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
 * <p>为什么异步：一次任务会推送成百上千个 delta 事件，如果每个事件都同步等一次
 * 远程 Redis RTT（10~30ms），流式输出会被明显拖慢。这里用单线程 + 有界队列：
 * 追加顺序由队列保证，与 seq 顺序一致；任务本身只做一次 {@code offer}。</p>
 *
 * <p>终态时调用 {@link #flush(String)} 等待队列排空，再写终态元数据，
 * 保证“状态是 COMPLETED”时日志里一定已经有 done 事件。</p>
 *
 * <p>队列满或写入失败不会打断任务：标记 replayDegraded，
 * 本机重连仍能从内存日志完整回放，跨进程回放则明确失败而不是静默丢事件。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskEventMirror {

    /** 队列容量：按单事件约 0.5KB 估算，20 万条约 100MB 上限，实际远达不到 */
    private static final int QUEUE_CAPACITY = 200_000;

    private static final long FLUSH_TIMEOUT_SECONDS = 5;

    private final AgentTaskEventStore store;

    private final AgentTaskMetaService taskMetaService;

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final ConcurrentHashMap<String, Boolean> degradedMarked = new ConcurrentHashMap<>();

    private final Thread worker = new Thread(this::loop, "task-archive");

    private long dropped;

    @PostConstruct
    public void start() {
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        // 先停入口（append 会直接丢弃新事件），再让 worker 把队列里已有的事件写完，
        // 最后才 interrupt。直接 interrupt 会让 poll 抛异常跳出循环，队列里的尾部事件全丢。
        running.set(false);
        try {
            worker.join(FLUSH_TIMEOUT_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
            log.warn("[TaskMirror] 停机排空超时，剩余事件未归档: {}", queue.size());
        }
    }

    /**
     * 追加一条事件到归档队列（非阻塞）。
     */
    public void append(String taskId, AgentTaskEvent event) {
        if (!running.get()) {
            return;
        }
        if (!queue.offer(Task.event(taskId, event))) {
            dropped++;
            if (dropped % 100 == 1) {
                log.error("[TaskMirror] 归档队列已满，事件被丢弃: taskId={}, dropped={}",
                        taskId, dropped);
            }
            markDegraded(taskId);
        }
    }

    /**
     * 等待队列中此前入队的事件全部落库。终态屏障使用。
     */
    public void flush(String taskId) {
        if (!running.get()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (!queue.offer(Task.barrier(taskId, latch))) {
            log.warn("[TaskMirror] 队列已满，跳过终态屏障: taskId={}", taskId);
            markDegraded(taskId);
            return;
        }
        try {
            if (!latch.await(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[TaskMirror] 终态屏障等待超时: taskId={}", taskId);
                markDegraded(taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markDegraded(taskId);
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
        log.info("[TaskMirror] 归档线程退出，剩余任务={}", queue.size());
    }

    private void runTask(Task task) {
        if (task.barrier != null) {
            task.barrier.countDown();
            return;
        }
        try {
            store.appendEvent(task.taskId, task.event);
        } catch (Exception e) {
            markDegraded(task.taskId);
            if (degradedMarked.putIfAbsent(task.taskId, Boolean.TRUE) == null) {
                log.error("[TaskMirror] 事件归档失败，该生成只能本机回放: taskId={}, seq={}",
                        task.taskId, task.event.getSeq(), e);
            }
        }
    }

    private void markDegraded(String taskId) {
        if (taskMetaService == null || taskId == null) {
            return;
        }
        taskMetaService.markReplayDegraded(taskId);
    }

    /**
     * 队列任务：普通事件 or 屏蔽屏障。用一个类承载，避免额外对象分配。
     */
    private static final class Task {

        private final String taskId;

        private final AgentTaskEvent event;

        private final CountDownLatch barrier;

        private Task(String taskId, AgentTaskEvent event, CountDownLatch barrier) {
            this.taskId = taskId;
            this.event = event;
            this.barrier = barrier;
        }

        static Task event(String taskId, AgentTaskEvent event) {
            return new Task(taskId, event, null);
        }

        static Task barrier(String taskId, CountDownLatch latch) {
            return new Task(taskId, null, latch);
        }
    }
}
