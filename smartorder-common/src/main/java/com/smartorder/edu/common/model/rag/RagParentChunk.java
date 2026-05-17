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
public class RagParentChunk {
    private String parentChunkKey;
    private String title;
    private String headingPath;
    private String content;
    private String summary;
    private Integer chunkIndex;
    private String sourceLocator;
    private Map<String, Object> metadata;
}
