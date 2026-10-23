package com.smartorder.edu.common.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Milvus 客户端提供者：懒连接 + 失败降级 + 自动重连。
 *
 * <p>背景：早期实现直接在 {@code @Bean} 方法里 {@code new MilvusClientV2(...)}，
 * 该构造器会同步发起 gRPC connect 请求。一旦 Milvus 不可达（网络抖动、服务重启、
 * 建连耗时超过 SDK 内部超时），Bean 创建失败会沿
 * Controller → Service → VectorIndexService 的依赖链向上冒泡，
 * 最终导致整个 Spring 容器启动失败，与"向量检索只是可选能力"的定位不符。</p>
 *
 * <p>改为懒连接后：</p>
 * <ul>
 *   <li>应用启动阶段不再触碰 Milvus，Milvus 宕机不影响主业务启动；</li>
 *   <li>首次真正需要检索/写入时才建连，建连失败返回 {@code null}，由调用方降级；</li>
 *   <li>失败后进入冷却期（默认 30s），避免每次请求都阻塞在超时上；</li>
 *   <li>冷却期结束会自动重试，Milvus 恢复后无需重启应用。</li>
 * </ul>
 */
@Slf4j
@Component
public class MilvusClientProvider {

    @Value("${milvus.host:127.0.0.1}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.database:smart_order_db}")
    private String database;

    /** gRPC 建连超时，跨公网访问时需适当放大 */
    @Value("${milvus.connect-timeout-ms:10000}")
    private long connectTimeoutMs;

    /** 单次 RPC 超时，0 表示不限制 */
    @Value("${milvus.rpc-deadline-ms:10000}")
    private long rpcDeadlineMs;

    /** 建连失败后的冷却时间，期间直接降级，不再重试 */
    @Value("${milvus.retry-cooldown-ms:30000}")
    private long retryCooldownMs;

    /**
     * 单次 get() 内的建连尝试次数。
     *
     * <p>注意：milvus-sdk-java 2.4.4 的 {@code MilvusClientV2.connect()} 内部把 connect RPC 的
     * deadline 硬编码为 1 秒（{@code withDeadlineAfter(1, SECONDS)}），不读取
     * {@code connectTimeoutMs}/{@code rpcDeadlineMs}。跨公网首次建连（DNS + TCP + HTTP/2 握手）
     * 经常超过 1 秒，导致首次建连必然失败；但 channel 已经建立，第二次尝试通常几百毫秒即可成功。
     * 因此这里必须重试。</p>
     */
    @Value("${milvus.connect-retry-times:3}")
    private int connectRetryTimes;

    /** 每次建连重试的间隔 */
    @Value("${milvus.connect-retry-interval-ms:500}")
    private long connectRetryIntervalMs;

    private final Object lock = new Object();

    private volatile MilvusClientV2 client;

    private volatile long nextRetryAt = 0L;

    /**
     * 获取 Milvus 客户端。
     *
     * @return 可用客户端；Milvus 不可用时返回 {@code null}，调用方需自行降级
     */
    public MilvusClientV2 get() {
        MilvusClientV2 current = this.client;
        if (current != null) {
            return current;
        }
        // 冷却期内直接返回 null，避免每个请求都卡在建连超时上
        if (System.currentTimeMillis() < this.nextRetryAt) {
            return null;
        }
        synchronized (lock) {
            if (this.client != null) {
                return this.client;
            }
            if (System.currentTimeMillis() < this.nextRetryAt) {
                return null;
            }
            int attempts = Math.max(1, connectRetryTimes);
            long start = System.currentTimeMillis();
            String lastError = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    ConnectConfig connectConfig = ConnectConfig.builder()
                            .uri("http://" + host + ":" + port)
                            .dbName(database)
                            .connectTimeoutMs(connectTimeoutMs)
                            .rpcDeadlineMs(rpcDeadlineMs)
                            .build();
                    MilvusClientV2 created = new MilvusClientV2(connectConfig);
                    this.client = created;
                    this.nextRetryAt = 0L;
                    log.info("[Milvus] 连接成功: {}:{}, db={}, 第 {} 次尝试, 总耗时 {} ms",
                            host, port, database, attempt, System.currentTimeMillis() - start);
                    return created;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    if (attempt < attempts) {
                        log.info("[Milvus] 第 {}/{} 次建连失败（{}），{} ms 后重试",
                                attempt, attempts, lastError, connectRetryIntervalMs);
                        sleepQuietly(connectRetryIntervalMs);
                    }
                }
            }
            this.nextRetryAt = System.currentTimeMillis() + retryCooldownMs;
            log.warn("[Milvus] 连续 {} 次建连失败，{} ms 内降级处理（向量检索/索引暂不可用）: {}:{}, db={}, 总耗时 {} ms, 最后错误: {}",
                    attempts, retryCooldownMs, host, port, database,
                    System.currentTimeMillis() - start, lastError);
            return null;
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 客户端是否已建立连接（不代表服务端此刻一定可用）。
     */
    public boolean isConnected() {
        return this.client != null;
    }

    /**
     * 丢弃当前连接，下次 {@link #get()} 时重新建连。
     * 适用于调用过程中捕获到连接类异常的场景。
     */
    public void reset() {
        synchronized (lock) {
            closeQuietly(this.client);
            this.client = null;
            this.nextRetryAt = 0L;
        }
        log.info("[Milvus] 已重置连接，下次调用将重新建连");
    }

    @PreDestroy
    public void destroy() {
        synchronized (lock) {
            closeQuietly(this.client);
            this.client = null;
        }
    }

    private void closeQuietly(MilvusClientV2 target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception e) {
            log.debug("[Milvus] 关闭连接时忽略异常: {}", e.getMessage());
        }
    }
}
