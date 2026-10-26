package com.smartorder.edu.agent.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentTaskStatus;
import com.smartorder.edu.agent.runtime.store.AgentTaskEventStore;
import com.smartorder.edu.agent.service.AgentTaskMetaService;
import com.smartorder.edu.agent.runtime.AgentTaskEvent;
import com.smartorder.edu.agent.runtime.AgentTaskMeta;
import com.smartorder.edu.agent.runtime.AgentTaskRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 中断回复恢复——把 Redis Stream 里已经生成的内容回填到 MySQL。
 *
 * <p><b>为什么需要它</b>：正文只在任务正常结束时由 completeReply 一次性落库；
 * 进程重启/崩溃时 assistant 占位行停在 RUNNING 且 content 为空。而正文增量
 * （{@code agent.text.delta}）本来就已经在 Redis Stream 里（每个事件都会镜像写入），
 * 只是重启后没有任何人回头读它。这里在启动与定时清扫时从 Stream 重建正文，
 * 把“用户已经看到过的回答”补回库里，而不是让它退化成一枚空白的 FAILED 气泡。</p>
 *
 * <p><b>任务关联</b>：{@code conversation_message.task_id} 与
 * {@code agent_chat_task.task_id} 是同一个值，不需要再通过会话索引反查，
 * 也不会把别的任务正文写进当前气泡。</p>
 *
 * <p><b>与在线链路的分工</b>：本类只处理“库里是 RUNNING、但本机已经没有执行者”的占位行；
 * 本机仍持有 live 任务的（执行线程还活着）一律跳过，交给正常收尾。</p>
 *
 * <p><b>多实例注意</b>：判活只看本机 {@link AgentTaskRegistry}，与任务的单实例执行语义一致；
 * 多实例部署需要网关粘滞会话，否则别的节点在跑的任务会被误判为中断。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptedReplyRecovery {

    /** 任务超时阈值（分钟）——需大于 SSE 超时（180s）与计划执行超时（300s）的常规耗时 */
    private static final int TIMEOUT_MINUTES = 5;

    private static final String MESSAGE_WITH_CONTENT = "任务中断（服务重启或进程异常退出），已保留已生成的部分内容";

    private static final String MESSAGE_EMPTY = "任务中断（服务重启或进程异常退出），请重新生成";

    private final ConversationMessageMapper messageMapper;

    private final AgentTaskMetaService taskMetaService;

    private final AgentTaskEventStore eventStore;

    private final AgentTaskRegistry registry;

    /**
     * 启动清扫：此刻本机没有任何在跑的任务，所有 RUNNING 占位行都是僵尸。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover(true);
    }

    /**
     * 定时清扫：兜住“进程没重启、但执行线程已经消失”的情况（线程被回收、实例漂移）。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void recoverPeriodically() {
        recover(false);
    }

    private void recover(boolean startup) {
        List<ConversationMessage> rows;
        try {
            rows = startup
                    ? messageMapper.selectRunningAssistantMessages()
                    : messageMapper.selectStaleRunningAssistantMessages(
                            LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES));
        } catch (Exception e) {
            log.error("[ReplyRecovery] 查询 RUNNING 占位行失败", e);
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int closed = 0;
        int withContent = 0;
        for (ConversationMessage row : rows) {
            try {
                if (recoverOne(row)) {
                    withContent++;
                }
                closed++;
            } catch (Exception e) {
                log.warn("[ReplyRecovery] 单条恢复失败: messageId={}, cid={}, reason={}",
                        row.getId(), row.getConversationId(), e.getMessage());
            }
        }
        log.info("[ReplyRecovery] 中断回复收敛完成: total={}, closed={}, withContent={}",
                rows.size(), closed, withContent);
    }

    /**
     * @return 是否从 Redis Stream 重建出了正文
     */
    private boolean recoverOne(ConversationMessage row) {
        AgentTaskMeta meta = resolveMeta(row);
        if (meta != null && registry.getLive(meta.getTaskId()) != null) {
            // 本机还在跑：不是僵尸，等它自己收尾
            return false;
        }
        String content = meta == null ? null : rebuildContent(meta);
        boolean hasContent = content != null && !content.isBlank();

        if (meta != null && meta.statusEnum() == AgentTaskStatus.COMPLETED && hasContent) {
            // 极端窗口：任务已终态但占位行没来得及收尾（例如 completeReply 抛异常）
            messageMapper.markRecoveredCompleted(row.getId(), content);
            taskMetaService.updateTerminalState(meta.getTaskId(), AgentTaskStatus.COMPLETED,
                    lastSeq(meta.getTaskId()), null, row.getId(), meta.isReplayable());
            log.info("[ReplyRecovery] 任务已终态，补写完成: messageId={}, taskId={}",
                    row.getId(), meta.getTaskId());
            return true;
        }

        int updated = messageMapper.markInterrupted(row.getId(), hasContent ? content : null,
                hasContent ? MESSAGE_WITH_CONTENT : MESSAGE_EMPTY);
        if (meta != null) {
            // 消息行和任务行一起收敛；否则 activeTask 会一直返回一个永远 RUNNING 的任务。
            taskMetaService.updateTerminalState(meta.getTaskId(), AgentTaskStatus.INTERRUPTED,
                    lastSeq(meta.getTaskId()), hasContent ? MESSAGE_WITH_CONTENT : MESSAGE_EMPTY,
                    row.getId(), meta.isReplayable());
        }
        if (updated > 0) {
            log.warn("[ReplyRecovery] 标记中断: messageId={}, cid={}, taskId={}, recoveredChars={}",
                    row.getId(), row.getConversationId(),
                    meta == null ? null : meta.getTaskId(), hasContent ? content.length() : 0);
        }
        return hasContent;
    }

    /**
     * 按占位行的 task_id 读取任务元数据；无法确定时返回 null（退化为“只标失败、不填正文”）。
     */
    private AgentTaskMeta resolveMeta(ConversationMessage row) {
        if (row.getTaskId() == null || row.getTaskId().isBlank()) {
            log.warn("[ReplyRecovery] 占位行缺少 task_id，无法从 Stream 恢复正文: messageId={}, cid={}",
                    row.getId(), row.getConversationId());
            return null;
        }
        AgentTaskMeta meta;
        try {
            meta = taskMetaService.find(row.getTaskId());
        } catch (Exception e) {
            log.warn("[ReplyRecovery] 任务元数据读取失败: taskId={}, reason={}",
                    row.getTaskId(), e.getMessage());
            return null;
        }
        if (meta == null) {
            return null;
        }
        // 绑定过的任务必须指向这一行占位，否则会把别的回答写进这条气泡
        if (meta.getAssistantMessageId() != null
                && !Objects.equals(meta.getAssistantMessageId(), row.getId())) {
            log.debug("[ReplyRecovery] 任务与占位行不匹配: messageId={}, meta.assistantMessageId={}",
                    row.getId(), meta.getAssistantMessageId());
            return null;
        }
        return meta;
    }

    /**
     * 从 Redis Stream 重建正文：把 {@code agent.text.delta} 的 payload.text 按 seq 拼起来。
     *
     * <p>Stream 里的事件与 SSE 下发的 data 行逐字一致，所以拼出来的正文与用户当时看到的一致。
     * 多气泡场景（一轮内第二次及以后的气泡）不会走到这里——那些气泡是正常收尾时才插入的。</p>
     */
    private String rebuildContent(AgentTaskMeta meta) {
        List<AgentTaskEvent> events;
        try {
            events = eventStore.readAfter(meta.getTaskId(), 0L);
        } catch (Exception e) {
            log.warn("[ReplyRecovery] Stream 读取失败: taskId={}, reason={}",
                    meta.getTaskId(), e.getMessage());
            return null;
        }
        if (events == null || events.isEmpty()) {
            return null;
        }
        String textDelta = AgentEventType.TEXT_DELTA.getEventName();
        StringBuilder content = new StringBuilder();
        for (AgentTaskEvent event : events) {
            if (!textDelta.equals(event.getEventName())) {
                continue;
            }
            String text = extractText(event.getData());
            if (text != null && !text.isEmpty()) {
                content.append(text);
            }
        }
        return content.isEmpty() ? null : content.toString();
    }

    private String extractText(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            JSONObject event = JSON.parseObject(data);
            JSONObject payload = event == null ? null : event.getJSONObject("payload");
            return payload == null ? null : payload.getString("text");
        } catch (Exception e) {
            // 单条事件坏了不能拖垮整轮恢复
            return null;
        }
    }

    private long lastSeq(String taskId) {
        try {
            return eventStore.lastEventSeq(taskId);
        } catch (Exception e) {
            return 0L;
        }
    }
}
