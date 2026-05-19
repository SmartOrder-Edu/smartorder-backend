package com.smartorder.edu.common.model.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorIndexDocument {
    private String id;
    private String text;
    private Map<String, Object> metadata;
}
