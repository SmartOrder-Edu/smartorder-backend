package com.smartorder.edu.common.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileProcessMessage implements Serializable {
    private Long jobId;
    private Long userId;
    private Long knowledgeBaseId;
    private Long fileId;
}
