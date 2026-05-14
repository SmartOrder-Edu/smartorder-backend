package com.smartorder.edu.common.domain.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileDTO {
    private String originalName;
    private String storedName;
    private String fileUrl;
    private Long fileSize;
    private String fileHash;
    private Long knowledgeBaseId;
    private Long userId;
}
