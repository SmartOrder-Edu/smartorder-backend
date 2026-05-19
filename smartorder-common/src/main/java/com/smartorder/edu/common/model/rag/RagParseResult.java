package com.smartorder.edu.common.model.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagParseResult {
    private Long fileId;
    private Long userId;
    private Long knowledgeBaseId;
    private String fileName;
    private String fileType;
    private String rawText;
    private String markdown;
    private String documentSummary;
    private List<RagParentChunk> parentChunks;
    private List<RagChildChunk> childChunks;
}
