package com.smartorder.edu.common.util;

/**
 * 14位唯一ID生成器（Long类型）
 * 格式：时间戳(秒) 8位 + 机器ID(2位) + 序列号(4位) = 14位纯数字
 * 支持每秒生成 10000 个唯一ID，分布式安全
 */
public class IdGenerator {

    // 起始时间戳（2025-01-01 00:00:00，可自行修改）
    private static final long EPOCH = 1735689600L;

    // 机器ID 占 2 位（0~99，分布式部署时每个实例不同）
    private static final long WORKER_ID_BITS = 2L;
    private static final long MAX_WORKER_ID = 99L;

    // 序列号 占 4 位（0~9999）
    private static final long SEQUENCE_BITS = 4L;
    private static final long MAX_SEQUENCE = 9999L;

    private static long workerId = 0; // 机器ID，可配置
    private static long lastTimestamp = -1L;
    private static long sequence = 0L;

    // 锁保证线程安全
    private static final Object lock = new Object();

    /**
     * 生成 14 位唯一 Long 型 ID
     */
    public static long nextId() {
        synchronized (lock) {
            long timestamp = timeGen();

            // 发生时间回拨，直接报错（强一致性）
            if (timestamp < lastTimestamp) {
                throw new RuntimeException("时间回拨，无法生成ID");
            }

            // 同一秒内自增序列号
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) % (MAX_SEQUENCE + 1);
                // 序列号用尽，等待下一秒
                if (sequence == 0) {
                    timestamp = waitNextSecond(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = timestamp;

            // 拼接 14 位 ID
            return (timestamp - EPOCH) * 1000000L  // 8位时间戳
                    + workerId * 10000L           // 2位机器ID
                    + sequence;                   // 4位序列号
        }
    }

    /**
     * 等待下一秒
     */
    private static long waitNextSecond(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 获取当前秒级时间戳
     */
    private static long timeGen() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 设置机器ID（分布式环境必须设置，0~99）
     */
    public static void setWorkerId(long id) {
        if (id < 0 || id > MAX_WORKER_ID) {
            throw new IllegalArgumentException("机器ID必须在 0~99 之间");
        }
        workerId = id;
    }
}