package com.smartorder.edu.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话主表实体。
 * <p>
 * public_id 承载原有的 conversation_id（UUID 字符串），
 * conversation_message.conversation_id 与之对应，存量数据零改写。
 * 内部自增 id 只用于行锁（SELECT ... FOR UPDATE）与关联，不对外暴露。
 */
@Data
@NoArgsConstructor
@TableName("conversation")
public class Conversation {

    /** 正常 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 归档（预留） */
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    /** 已删除（软删） */
    public static final String STATUS_DELETED = "DELETED";

    /** 标题来源：首条消息截断 */
    public static final String TITLE_SOURCE_AUTO = "AUTO";
    /** 标题来源：模型总结 */
    public static final String TITLE_SOURCE_LLM = "LLM";
    /** 标题来源：用户改名 */
    public static final String TITLE_SOURCE_USER = "USER";

    /**
     * 内部主键（雪花 ID）。
     * <p>
     * 用 {@link IdType#INPUT} 而非 {@code AUTO}：主键由
     * {@code ConversationService#createIfAbsent} 通过
     * {@code cn.hutool.core.util.IdUtil#getSnowflakeNextId()} 显式生成，
     * 与项目内 user_id / file_id 的生成方式保持一致，避免依赖数据库自增
     * （多实例、数据迁移、分库分表场景下自增会冲突）。
     * <p>
     * 该字段只用于锁行与内部关联，不对外暴露，对外统一用 {@link #publicId}。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String publicId;

    private Long userId;

    private String title;

    private String titleSource;

    private String status;

    private Integer messageCount;

    private LocalDateTime lastMessageAt;

    private String lastMessagePreview;

    private Integer pinned;

    private LocalDateTime pinnedAt;

    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
