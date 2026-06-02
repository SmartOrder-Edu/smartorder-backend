package com.smartorder.edu.manage.domain.dto;

import lombok.Data;

/**
 * 奖品后台分页检索条件 DTO
 */
@Data
public class PrizeQueryDTO {
    
    /**
     * 页码
     */
    private Integer page = 1;
    
    /**
     * 数量
     */
    private Integer size = 10;
    
    /**
     * 奖品名称（模糊查询）
     */
    private String name;
    
    /**
     * 卡密发放状态：0=未发放，1=已发放，2=已废弃
     */
    private Integer status;
}
