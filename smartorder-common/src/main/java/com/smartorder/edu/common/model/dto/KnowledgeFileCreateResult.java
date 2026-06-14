package com.smartorder.edu.common.model.dto;

import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileCreateResult {
    private KnowledgeFile file;
    private KnowledgeFileJob job;
}
