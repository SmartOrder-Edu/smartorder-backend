package com.smartorder.edu.student.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 资源评分请求 DTO
 */
@Data
public class ResourceSquareRateDTO {

    @NotNull(message = "评分不能为空")
    @Min(1)
    @Max(5)
    private Integer rating;
}
