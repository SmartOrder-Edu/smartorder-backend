package com.smartorder.edu.student.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单个画像维度的增量更新请求。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StudentProfileDimensionUpdateDTO {

    private String dimensionKey;

    private Map<String, Object> delta;

    private String reason;
}
