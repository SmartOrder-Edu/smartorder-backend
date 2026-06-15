package com.smartorder.edu.student.domain.dto.student.dto;

import lombok.Data;

import java.util.List;

/**
 * 画像维度通用元数据 DTO
 * <p>各维度 JSON 共用的 metadata 结构，记录数据来源、置信度、更新时间及有效期</p>
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
public class DimensionMetadataDTO {

    /** Schema 版本号 */
    private String schemaVersion;

    /** 维度整体置信度 0~1 */
    private Double confidence;

    /** 维度完整度 0~100 */
    private Integer completeness;

    /** 数据来源类型列表：USER_EXPLICIT / QUIZ_RESULT / CODE_EVALUATION / SYSTEM_DERIVED / ... */
    private List<String> sourceTypes;

    /** 证据ID列表 */
    private List<String> evidenceIds;

    /** 最近更新时间 */
    private String lastUpdatedAt;

    /** 更新者：PROFILE_AGENT / ASSESSMENT_AGENT / ... */
    private String updatedBy;

    /** 更新原因 */
    private String updateReason;

    /** 有效期限，null 表示长期有效 */
    private String validUntil;
}
