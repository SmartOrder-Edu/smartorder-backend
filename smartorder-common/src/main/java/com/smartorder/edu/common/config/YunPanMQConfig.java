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
 * <p>
 * <b>队列 arguments 不可变更（踩过的坑）</b>：Broker 上的队列一旦创建，arguments（x-dead-letter-exchange、
 * x-message-ttl、x-queue-type 等）就固定了，后续 redeclare 必须与既存定义<b>逐项相等</b>，否则 Broker 返回
 * {@code 406 PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue '...':
 * received the value '...' of type 'longstr' but current is none}，并关闭 channel，
 * 导致该队列的监听容器起不来、消息只进不出。
 * <p>
 * 所以给<b>已经存在</b>的队列新增/修改参数时只有两条路：
 * ① 在 Broker 上删除旧队列（积压消息一并丢弃，需先确认深度）后由应用重建；
 * ② 换一个新队列名（如加 {@code .v2} 后缀）并行迁移，旧队列消费完再下线。
 * 改代码并不会改变 Broker 上已有队列的定义。
 */
@Configuration
public class YunPanMQConfig {
    // 交换机
    public static final String MQ_YUN_PAN_EXCHANGE = "yun.pan.exchange";

    // 队列
    public static final String MQ_QUEUE_FILE_DB_QUOTE = "yun.pan.file.db.quote.queue";

    /**
     * 秒传入库队列。
     * <p>
     * 带 {@code .v2} 后缀是<b>参数变更引起的迁移</b>，不是笔误：Broker 上历史存在的
     * {@code yun.pan.file.save.queue} 是"无死信参数"的旧定义，而队列 arguments 创建后不可修改
     * （redeclare 必须逐项相等，否则 406 PRECONDITION_FAILED），删队列又会被仍在运行的旧实例立刻重建。
     * 故改用新队列名并行迁移：v1 不删也不影响新代码，v2 由本应用独立声明并携带死信参数。
     */
    public static final String MQ_QUEUE_FILE_SAVE = "yun.pan.file.save.v2.queue";

    // 路由键
    public static final String MQ_ROUTING_KEY_FILE_QUOTE = "yun.pan.file.db.quote.key";

    /**
     * 秒传入库路由键，随队列一起加 {@code .v2}。
     * <p>
     * 必须同步改：若沿用旧 key，Broker 上 v1 队列的历史 binding 仍然匹配，一条消息会被同时投递到
     * v1/v2 两个队列，旧实例仍在跑时就会重复消费（入库靠主键幂等能兜住，但属于无谓开销与日志噪音）。
     */
    public static final String MQ_ROUTING_KEY_FILE_SAVE = "yun.pan.file.save.v2.key";

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
