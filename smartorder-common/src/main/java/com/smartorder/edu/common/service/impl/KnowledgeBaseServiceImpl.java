package com.smartorder.edu.common.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.common.model.dto.KnowledgeBaseReqDTO;
import com.smartorder.edu.common.model.entity.KnowledgeBase;
import com.smartorder.edu.common.model.vo.KnowledgeBaseListRespVO;
import com.smartorder.edu.common.mapper.KnowledgeBaseMapper;
import com.smartorder.edu.common.service.IKnowledgeBaseService;
import com.smartorder.edu.common.util.IdGenerator;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;


@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements IKnowledgeBaseService {
    /**
     * 创建知识库
     */
    public Long createKnowledgeBase(KnowledgeBaseReqDTO reqDTO, Long currentUserId) {
        KnowledgeBase kb = new KnowledgeBase();

        // 1. 拷贝前端传入的基础属性 (name, category, description, coverUrl)
        BeanUtils.copyProperties(reqDTO, kb);

        // 2. 补充业务与系统字段
        kb.setUserId(currentUserId);
        kb.setStatus("active");

        // 3. 设置 RAG 默认参数 (根据你的业务需求调整默认值)
        kb.setEmbeddingModel("text-embedding-3-small"); // 默认向量模型
        kb.setChunkStrategy("recursive");             // 默认递归分块
        kb.setRetrievalTopK(5);                       // 默认召回 5 个 chunk
        kb.setScoreThreshold(new BigDecimal("0.70")); // 默认相似度阈值

        // 4. 初始化统计字段
        kb.setFileCount(0);
        kb.setDocumentCount(0);
        kb.setChunkCount(0);
        kb.setTotalSize(0L);

        // 5. 设置时间
        LocalDateTime now = LocalDateTime.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setLastUpdatedAt(now);

        // 分布式环境：每个服务实例设置不同机器ID（0~99）
        IdGenerator.setWorkerId(1);
        // 生成14位唯一ID（Long类型）
        long id = IdGenerator.nextId();
        kb.setId(id);
        // 6. 保存到数据库
        this.save(kb);

        return kb.getId();
    }

    @Override
    public List<KnowledgeBaseListRespVO> getKnowledgeBaseList(Long currentUserId) {
        // 1. 查询当前用户下的所有 active 状态的知识库，按更新时间降序排列
        List<KnowledgeBase> knowledgeBases = this.lambdaQuery()
                .eq(KnowledgeBase::getUserId, currentUserId)
                .eq(KnowledgeBase::getStatus, "active")
                .orderByDesc(KnowledgeBase::getUpdatedAt)
                .list();

        // 2. 将实体类转换为前端所需的 VO 对象
        return knowledgeBases.stream().map(kb -> {
            KnowledgeBaseListRespVO vo = new KnowledgeBaseListRespVO();
            BeanUtils.copyProperties(kb, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Boolean updateKnowledgeBase(KnowledgeBaseReqDTO reqDTO, Long currentUserId) {
        // 1. 校验知识库是否存在，且该知识库属于当前用户 (防止越权修改)
        KnowledgeBase existingKb = this.lambdaQuery()
                .eq(KnowledgeBase::getId, reqDTO.getId())
                .eq(KnowledgeBase::getUserId, currentUserId)
                .one();

        if (existingKb == null) {
            throw new RuntimeException("知识库不存在或您无权修改该知识库");
        }

        // 2. 更新允许修改的字段
        existingKb.setName(reqDTO.getName());
        existingKb.setCategory(reqDTO.getCategory());
        existingKb.setDescription(reqDTO.getDescription());

        // 如果前端传了新的封面URL，则更新
        if (reqDTO.getCoverUrl() != null && !reqDTO.getCoverUrl().isEmpty()) {
            existingKb.setCoverUrl(reqDTO.getCoverUrl());
        }

        // 3. 更新时间
        existingKb.setUpdatedAt(LocalDateTime.now());

        // 4. 执行更新
        return this.updateById(existingKb);
    }
}
