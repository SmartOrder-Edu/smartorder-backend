package com.smartorder.edu.common.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseListRespVO {

    private Long id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库分类
     */
    private String category;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 知识库封面图地址
     */
    private String coverUrl;

    /**
     * 知识库下文件数量
     */
    private Integer fileCount;

    /**
     * 知识库下文档数量
     */
    private Integer documentCount;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}