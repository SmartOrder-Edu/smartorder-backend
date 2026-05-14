package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.dto.KnowledgeBaseReqDTO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseListRespVO;

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

}
