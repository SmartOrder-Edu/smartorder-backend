package com.smartorder.edu.common.model.vo;

import lombok.Data;

@Data
public class KnowledgeBaseStatsVO {

    /**
     * 知识库总数
     */
    private Integer knowledgeBaseCount;

    /**
     * 累积文件数
     */
    private Integer fileCount;

    /**
     * 总知识切片数量
     */
    private Integer chunkCount;
}
