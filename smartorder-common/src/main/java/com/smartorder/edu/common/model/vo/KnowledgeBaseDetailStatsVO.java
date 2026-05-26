package com.smartorder.edu.common.model.vo;

import lombok.Data;

@Data
public class KnowledgeBaseDetailStatsVO {

    /**
     * 总文件数
     */
    private Integer fileCount;

    /**
     * 总切片数
     */
    private Integer chunkCount;

    /**
     * 向量化完成文件数
     */
    private Integer embeddedFileCount;
}
