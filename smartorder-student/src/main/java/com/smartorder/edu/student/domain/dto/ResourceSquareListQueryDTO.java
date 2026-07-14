package com.smartorder.edu.student.domain.dto;

import lombok.Data;

/**
 * 资源广场列表查询 DTO
 */
@Data
public class ResourceSquareListQueryDTO {

    /** 搜索关键词（标题/描述/标签） */
    private String keyword;

    /** 分类筛选 */
    private String category;

    /** 排序：newest / download / rating */
    private String sortBy;

    private Integer pageNum = 1;

    private Integer pageSize = 12;
}
