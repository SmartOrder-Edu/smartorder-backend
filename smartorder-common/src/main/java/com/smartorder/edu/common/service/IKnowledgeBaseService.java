package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.dto.KnowledgeBaseReqDTO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseDetailStatsVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseListRespVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseStatsVO;

import java.util.List;

public interface IKnowledgeBaseService {
    /**
     * 创建知识库
     */
    Long createKnowledgeBase(KnowledgeBaseReqDTO reqDTO, Long currentUserId);

    /**
     * 获取当前用户的知识库列表
     */
    List<KnowledgeBaseListRespVO> getKnowledgeBaseList(Long currentUserId);

    /**
     * 编辑知识库
     */
    Boolean updateKnowledgeBase(KnowledgeBaseReqDTO reqDTO, Long currentUserId);

    /**
     * 删除知识库（级联删除所有相关数据）
     */
    void deleteKnowledgeBase(Long knowledgeBaseId, Long currentUserId);

    /**
     * 获取当前用户的知识库统计信息
     */
    KnowledgeBaseStatsVO getKnowledgeBaseStats(Long currentUserId);

    /**
     * 获取指定知识库的详细统计信息
     */
    KnowledgeBaseDetailStatsVO getKnowledgeBaseDetailStats(Long knowledgeBaseId, Long currentUserId);

}
