package com.smartorder.edu.common.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class YunPanMQConfig {
    // 交换机
    public static final String MQ_YUN_PAN_EXCHANGE = "yun.pan.exchange";

    // 队列
    public static final String MQ_QUEUE_FILE_DB_QUOTE = "yun.pan.file.db.quote.queue";
    public static final String MQ_QUEUE_FILE_SAVE = "yun.pan.file.save.queue";

    // 路由键
    public static final String MQ_ROUTING_KEY_FILE_QUOTE = "yun.pan.file.db.quote.key";
    public static final String MQ_ROUTING_KEY_FILE_SAVE = "yun.pan.file.save.key";
}
