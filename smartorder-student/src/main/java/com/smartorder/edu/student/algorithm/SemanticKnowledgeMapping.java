package com.smartorder.edu.student.algorithm;

import java.math.BigDecimal;

public record SemanticKnowledgeMapping(
        Long knowledgeNodeId,
        BigDecimal weight,
        BigDecimal confidence,
        String source,
        String reason
) {
}
