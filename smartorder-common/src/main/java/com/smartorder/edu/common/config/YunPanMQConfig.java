package com.smartorder.edu.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 云盘（个人网盘）MQ 拓扑。
 * <p>
 * 业务队列在 {@code @RabbitListener} 注解中声明（需要绑定关系），死信拓扑在这里以 @Bean 声明，
 * 与 knowledge 模块的 DLX 范式保持一致。
 */
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

    // ===== 死信（重试耗尽后的兜底，避免消息被静默丢弃） =====
    /** 死信交换机 */
    public static final String MQ_YUN_PAN_DLX = "yun.pan.dlx";

    /** 文件入库死信队列 */
    public static final String MQ_QUEUE_FILE_SAVE_DLQ = "yun.pan.file.save.dlq";

    /** 文件入库死信路由键 */
    public static final String MQ_ROUTING_KEY_FILE_SAVE_DLQ = "yun.pan.file.save.dlq.key";

    @Bean
    public DirectExchange yunPanDlx() {
        return ExchangeBuilder.directExchange(MQ_YUN_PAN_DLX).durable(true).build();
    }

    @Bean
    public Queue fileSaveDlq() {
        return QueueBuilder.durable(MQ_QUEUE_FILE_SAVE_DLQ).build();
    }

    @Bean
    public Binding fileSaveDlqBinding(@Qualifier("fileSaveDlq") Queue fileSaveDlq,
                                      @Qualifier("yunPanDlx") DirectExchange yunPanDlx) {
        return BindingBuilder.bind(fileSaveDlq).to(yunPanDlx).with(MQ_ROUTING_KEY_FILE_SAVE_DLQ);
    }
}
