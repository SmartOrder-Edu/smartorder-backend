package com.smartorder.edu.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息持久化实体
 * <p>
 * v2 扩展：会话内序号（sequence_no）、消息状态机（status）、
 * 版本化重生成（parent_message_id / attempt_no / is_visible）、
 * 生成指标（model_name / tokens / latency_ms）。
 */
@Data
@NoArgsConstructor
public class ConversationMessage {

    /** 生成中（占位行） */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 生成完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 生成失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 用户中止 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * 消息主键：应用侧雪花 ID（前端用它替换本地临时消息 ID）。
     * 显式声明 ASSIGN_ID，不依赖全局 mybatis-plus 配置。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String conversationId;
    private Long userId;
    private String role;
    private String content;
    private String reasoningContent;
    private String traceId;

    /** 生成这条消息的对话任务 ID；同一个 task 的 user / assistant 行共用 */
    private String taskId;

    /** 对外消息ID（UUID），前端只认这个 */
    private String publicId;

    /** 会话内序号，从 1 开始，由会话行锁分配 */
    private Integer sequenceNo;

    /** RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;

    /** 父消息：assistant 消息指向触发它的 user 消息 */
    private Long parentMessageId;

    /** 同一 parent 下的第几次生成（重新生成递增） */
    private Integer attemptNo;

    /** 版本切换：同一 parent 下仅一条为 1 */
    private Integer isVisible;

    /** 生成该消息的模型 */
    private String modelName;

    private Integer promptTokens;
    private Integer outputTokens;

    /** 占位行创建到收尾的耗时（毫秒） */
    private Integer latencyMs;

    /** 占位行创建时间（RUNNING 起点） */
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorMessage;

    private String toolName;
    private String attachmentFileIds;
    private String metadataJson;
    private Integer isCompressed;
    private Integer isDeleted;
    private LocalDateTime createTime;
}
