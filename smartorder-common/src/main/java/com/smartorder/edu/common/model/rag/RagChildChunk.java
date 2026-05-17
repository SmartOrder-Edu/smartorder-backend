package com.smartorder.edu.common.model.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChildChunk {
    private String chunkKey;
    private String parentChunkKey;
    private String title;
    private String headingPath;
    private String content;
    private String summary;
    private List<String> keywords;
    private String sourceType;
    private Integer chunkIndex;
    private Integer sectionLevel;
    private Integer pageNumber;
    private String chunkType;
    private Map<String, Object> metadata;
}
