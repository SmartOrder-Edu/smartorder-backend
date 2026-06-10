package com.smartorder.edu.common.model.rag;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AttachmentRagSearchResult {
    private String vectorId;
    private String content;
    private Long chunkId;
    private Long knowledgeBaseId;
    private Long fileId;
    private Long documentId;
    private Integer pageNumber;
    private String sectionTitle;
    private String titlePath;
    private Float score;
    private Map<String, Object> metadata;
}
