package com.smartorder.edu.agent.service;

/**
 * 一轮回复的“待收尾状态”存储（生产实现是 Redis）。
 *
 * <p><b>为什么不在 JVM 里</b>：{@code openReply} 在<b>请求线程</b>写，{@code completeReply} /
 * {@code failReply} / {@code cancelReply} 在<b>编排线程</b>读，中间还隔着一次线程池调度。
 * 这份状态本质是"跨线程、跨阶段共享"的，放在进程里的 {@code Map} 会有三个后果：
 * 进程重启后本轮必然收不了尾（只能等扫地任务兜底）、多实例各存一份、
 * 而且没人能保证它会过期（旧实现只在 Map 超过 512 条时懒清理）。</p>
 *
 * <p><b>TTL 就是它的生命周期</b>：一轮问答不会超过 {@code TTL}，写进去时设过期时间，
 * 收尾后立刻删除；即使进程崩在中间，Redis 也会自己清掉，不留下永久占位。</p>
 *
 * <p>存的是 JSON 快照，不是对象引用：读出来就是一份独立副本，
 * 不会出现"两个线程改同一个可变对象"的隐性竞争。</p>
 */
public interface PendingReplyStore {

    /** 记录本轮待收尾状态（覆盖写，带 TTL） */
    void save(String traceId, ConversationMessageService.PendingReply reply);

    /** 读取本轮待收尾状态；不存在或已过期返回 null */
    ConversationMessageService.PendingReply load(String traceId);

    /** 收尾完成后删除（幂等） */
    void remove(String traceId);

    /** 本轮是否还有未收尾的回复 */
    boolean exists(String traceId);
}
