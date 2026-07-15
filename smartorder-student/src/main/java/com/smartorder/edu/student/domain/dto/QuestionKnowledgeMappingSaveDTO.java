package com.smartorder.edu.student.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class QuestionKnowledgeMappingSaveDTO {

    @Valid
    @NotEmpty(message = "知识点映射不能为空")
    private List<Item> mappings;

    @Data
    public static class Item {
        @NotNull(message = "知识点ID不能为空")
        private Long knowledgeNodeId;

        @NotNull(message = "权重不能为空")
        @DecimalMin(value = "0.0001", message = "权重必须大于0")
        private BigDecimal weight;

        @DecimalMin(value = "0.0000", message = "置信度不能小于0")
        @DecimalMax(value = "1.0000", message = "置信度不能大于1")
        private BigDecimal confidence;

        private String reason;
    }
}
