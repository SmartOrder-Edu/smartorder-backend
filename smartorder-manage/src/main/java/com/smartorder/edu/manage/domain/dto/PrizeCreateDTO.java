package com.smartorder.edu.manage.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 管理员录入/导入卡密奖品 DTO
 */
@Data
public class PrizeCreateDTO {
    
    /**
     * 奖品名称（如：京东卡 50 元）
     */
    @NotBlank(message = "奖品名称不能为空")
    private String name;
    
    /**
     * 批量兑换码卡密列表
     */
    @NotEmpty(message = "录入卡密码列表不能为空")
    private List<String> prizeCodes;
}
