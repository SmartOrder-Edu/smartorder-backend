package com.smartorder.edu.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检索上下文
 * 在整个检索流程中传递信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalContext {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 查询文本
     */
    private String query;

    /**
     * 文件ID集合
     */
    private List<Long> fileIds;

    /**
     * 知识库ID集合
     */
    private List<Long> knowledgeBaseIds;

    /**
     * 返回结果数量
     */
    @Builder.Default
    private int topK = 10;

    /**
     * 查询分析结果
     */
    private QueryAnalysisResult analysisResult;

    /**
     * 检索路由结果
     */
    private RetrievalRouteResult routeResult;

    /**
     * 提取的实体列表（用于图检索）
     */
    private List<String> entities;
}
