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
public class MarkdownChunkResult {
    private String documentSummary;
    private List<RagParentChunk> parentChunks;
    private List<RagChildChunk> childChunks;
}
