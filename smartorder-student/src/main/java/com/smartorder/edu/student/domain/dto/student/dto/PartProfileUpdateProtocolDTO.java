package com.smartorder.edu.student.domain.dto.student.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 局部画像更新协议 DTO
 * <p>对应 JSON Schema: sencond/part_profile_update_protocol.json</p>
 * <p>事件驱动的增量更新协议，采用类 JSON Patch 操作对画像的指定维度/路径进行局部更新，
 * 避免全量覆盖导致并发冲突和数据丢失</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartProfileUpdateProtocolDTO {

    /** 期望的画像版本号，用于乐观锁校验 */
    private Integer expectedProfileVersion;

    /** 事件唯一标识 */
    private String eventId;

    /** 事件类型：DIALOGUE / QUIZ_RESULT / CODE_EVALUATION / SYSTEM_CRON / ... */
    private String eventType;

    /** 变更列表 */
    private List<ChangeDTO> changes;

    // ====== 内部 DTO ======

    /**
     * 单条变更操作
     */
    @Data
    public static class ChangeDTO {

        /** 操作类型：UPSERT / REMOVE */
        private String operation;

        /** 目标维度：learning_preference / subject_profiles / knowledge_mastery / ... */
        private String dimension;

        /** JSON Pointer 路径，如 /subject_overrides/DATA_STRUCTURES/preferred_resource_types */
        private String path;

        /** 变更值，类型随业务场景变化（数组、对象、标量） */
        private Object value;

        /** 值来源：USER_EXPLICIT / MODEL_INFERRED / SYSTEM_DERIVED / ... */
        private String valueSource;

        /** 置信度 0~1 */
        private Double confidence;

        /** 关联的证据ID列表 */
        private List<String> evidenceIds;

        /** 变更原因描述 */
        private String reason;
    }
}
