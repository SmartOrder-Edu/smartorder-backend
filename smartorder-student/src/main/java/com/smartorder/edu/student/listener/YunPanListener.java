package com.smartorder.edu.student.listener;

import com.smartorder.edu.common.model.dto.FileSaveMessage;
import com.smartorder.edu.student.service.IFileResourceService;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import static com.smartorder.edu.common.config.YunPanMQConfig.*;

/**
 * 云盘 MQ 监听器。
 * <p>
 * 消费端只做三件事：反序列化强类型消息 → 调用领域服务 → 决定 ack / nack。
 * 实体拼装、扩展名解析、配额结算等业务规则下沉到 {@link IFileResourceService}，
 * 便于单测与复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YunPanListener {

    private final IFileResourceService fileResourceService;
    private final IUserFileQuotaService userFileQuotaService;

    /**
     * 统计个人网盘容量（新用户注册时初始化配额）。
     * <p>
     * 参数声明为 {@code Long} 而非 {@code Object}：转换器配置的是 INFERRED 类型优先级，
     * 由方法参数类型决定反序列化目标。声明 Object 时 Jackson 会按数值范围推断类型
     * （int 范围 → Integer，超出 → Long，再超出 → BigInteger），19 位雪花 ID 恰好落在
     * Long 范围，所以原写法能跑通——但那是"碰巧成立"：换成自增主键或小数字就会退化成
     * Integer，{@code instanceof Long} 直接失效。显式声明 Long 才是把契约写死。
     * <p>
     * 初始化本身幂等（getQuota 不存在则建），消息重复投递或过期丢失都不会造成脏数据。
     */
    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(name = MQ_YUN_PAN_EXCHANGE, type = ExchangeTypes.DIRECT, durable = "true"),
            value = @Queue(name = MQ_QUEUE_FILE_DB_QUOTE, durable = "true"),
            key = {MQ_ROUTING_KEY_FILE_QUOTE}
    ))
    public void handleUserRegistered(Long userId) {
        if (userId == null) {
            log.warn("用户注册消息缺少 userId，无法初始化云盘配额");
            return;
        }
        log.info("为新用户 {} 初始化云盘配额", userId);
        // getQuota 内部"不存在则初始化"，对重复投递天然幂等（initQuota 并发冲突时会回查）
        userFileQuotaService.getQuota(userId);
    }

    /**
     * 保存文件到数据库（秒传分支）。
     * <p>
     * 异常处理原则：只捕获"可预期且重试无意义"的幂等冲突，其余异常一律向上抛。
     * 吞掉异常等于给 Broker 回 ack，消息会被直接删除且业务无补偿，属于最隐蔽的数据丢失。
     * <p>
     * 抛出的异常由容器按 {@code spring.rabbitmq.listener.simple.retry} 策略重试，
     * 耗尽后 {@code basicReject(requeue=false)} → 命中队列的 x-dead-letter-exchange → 进死信队列。
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    exchange = @Exchange(name = MQ_YUN_PAN_EXCHANGE, type = ExchangeTypes.DIRECT, durable = "true"),
                    value = @Queue(name = MQ_QUEUE_FILE_SAVE, durable = "true", arguments = {
                            @Argument(name = "x-dead-letter-exchange", value = MQ_YUN_PAN_DLX),
                            @Argument(name = "x-dead-letter-routing-key", value = MQ_ROUTING_KEY_FILE_SAVE_DLQ)
                    }),
                    key = {MQ_ROUTING_KEY_FILE_SAVE}
            ),
            concurrency = "2-4"
    )
    public void saveFileToDB(FileSaveMessage message) {
        log.info("收到秒传文件入库消息，fileId: {}, userId: {}", message.getFileId(), message.getUserId());
        try {
            fileResourceService.saveQuickUploadFile(message);
        } catch (DuplicateKeyException e) {
            // 主键冲突 = 这条消息之前已经成功消费过，属于幂等命中，按成功处理（回 ack）
            log.info("文件记录已存在，重复投递幂等跳过，fileId: {}", message.getFileId());
        }
    }

    /**
     * 文件入库死信队列：重试耗尽仍未成功的消息落到这里，只告警不抛异常
     * （死信队列本身没有下级死信，抛异常会导致消息彻底丢失）。
     */
    @RabbitListener(queues = MQ_QUEUE_FILE_SAVE_DLQ)
    public void handleFileSaveDlq(FileSaveMessage message) {
        log.error("【MQ 死信告警】秒传文件入库最终失败，需人工补偿。fileId: {}, userId: {}, fileName: {}, objectKey: {}",
                message.getFileId(), message.getUserId(), message.getFileName(), message.getObjectKey());
    }
}
