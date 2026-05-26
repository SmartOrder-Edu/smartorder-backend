package com.smartorder.edu.common.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.common.mapper.*;
import com.smartorder.edu.common.model.dto.KnowledgeBaseReqDTO;
import com.smartorder.edu.common.model.entity.*;
import com.smartorder.edu.common.model.vo.KnowledgeBaseListRespVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseDetailStatsVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseStatsVO;
import com.smartorder.edu.common.rag.VectorIndexService;
import com.smartorder.edu.common.service.IKnowledgeBaseService;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements IKnowledgeBaseService {

    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final RagReferenceMapper ragReferenceMapper;
    private final INeo4jKnowledgeGraphService neo4jKnowledgeGraphService;
    private final VectorIndexService vectorIndexService;
    private final AliOSSUtils aliOSSUtils;

    /**
     * 文件上传成功时，更新知识库统计
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementFileStats(Long knowledgeBaseId, long fileSize) {
        KnowledgeBase kb = this.getById(knowledgeBaseId);
        if (kb == null) {
            log.warn("知识库不存在，无法更新统计: knowledgeBaseId={}", knowledgeBaseId);
            return;
        }
        kb.setFileCount((kb.getFileCount() == null ? 0 : kb.getFileCount()) + 1);
        kb.setTotalSize((kb.getTotalSize() == null ? 0L : kb.getTotalSize()) + fileSize);
        kb.setUpdatedAt(LocalDateTime.now());
        kb.setLastUpdatedAt(LocalDateTime.now());
        this.updateById(kb);
        log.info("知识库文件统计已更新: knowledgeBaseId={}, fileCount={}, totalSize={}", 
                knowledgeBaseId, kb.getFileCount(), kb.getTotalSize());
    }

    /**
     * 文件处理完成时，更新知识库文档和分块统计
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementDocumentStats(Long knowledgeBaseId, int chunkCount) {
        KnowledgeBase kb = this.getById(knowledgeBaseId);
        if (kb == null) {
            log.warn("知识库不存在，无法更新统计: knowledgeBaseId={}", knowledgeBaseId);
            return;
        }
        kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
        kb.setChunkCount((kb.getChunkCount() == null ? 0 : kb.getChunkCount()) + chunkCount);
        kb.setUpdatedAt(LocalDateTime.now());
        kb.setLastUpdatedAt(LocalDateTime.now());
        this.updateById(kb);
        log.info("知识库文档统计已更新: knowledgeBaseId={}, documentCount={}, chunkCount={}", 
                knowledgeBaseId, kb.getDocumentCount(), kb.getChunkCount());
    }

    /**
     * 文件删除时，更新知识库统计
     */
    @Transactional(rollbackFor = Exception.class)
    public void decrementFileStats(Long knowledgeBaseId, long fileSize, int chunkCount) {
        KnowledgeBase kb = this.getById(knowledgeBaseId);
        if (kb == null) {
            log.warn("知识库不存在，无法更新统计: knowledgeBaseId={}", knowledgeBaseId);
            return;
        }
        kb.setFileCount(Math.max(0, (kb.getFileCount() == null ? 0 : kb.getFileCount()) - 1));
        kb.setDocumentCount(Math.max(0, (kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) - 1));
        kb.setChunkCount(Math.max(0, (kb.getChunkCount() == null ? 0 : kb.getChunkCount()) - chunkCount));
        kb.setTotalSize(Math.max(0L, (kb.getTotalSize() == null ? 0L : kb.getTotalSize()) - fileSize));
        kb.setUpdatedAt(LocalDateTime.now());
        kb.setLastUpdatedAt(LocalDateTime.now());
        this.updateById(kb);
        log.info("知识库文件统计已减少: knowledgeBaseId={}, fileCount={}, documentCount={}, chunkCount={}, totalSize={}", 
                knowledgeBaseId, kb.getFileCount(), kb.getDocumentCount(), kb.getChunkCount(), kb.getTotalSize());
    }
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
        if (reqDTO.getId() == null) {
            throw new RuntimeException("更新知识库时ID不能为空");
        }
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long knowledgeBaseId, Long currentUserId) {
        // 1. 校验知识库是否存在，且该知识库属于当前用户
        KnowledgeBase existingKb = this.lambdaQuery()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, currentUserId)
                .one();

        if (existingKb == null) {
            throw new RuntimeException("知识库不存在或您无权删除该知识库");
        }

        log.info("开始删除知识库，knowledgeBaseId={}, userId={}", knowledgeBaseId, currentUserId);

        try {
            // 2. 删除 Neo4j 中的图节点
            try {
                neo4jKnowledgeGraphService.deleteNodesByKnowledgeBaseId(knowledgeBaseId);
            } catch (Exception e) {
                log.warn("删除 Neo4j 节点失败，继续执行", e);
            }

            // 3. 删除 Milvus 中的向量
            try {
                vectorIndexService.deleteByKnowledgeBaseId(knowledgeBaseId);
            } catch (Exception e) {
                log.warn("删除 Milvus 向量失败，继续执行", e);
            }

            // 4. 查询所有文件，用于删除 OSS 文件
            List<KnowledgeFile> files = knowledgeFileMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                            .eq(KnowledgeFile::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 5. 删除 OSS 中的文件
            for (KnowledgeFile file : files) {
                try {
                    if (file.getFileUrl() != null && !file.getFileUrl().isEmpty()) {
                        aliOSSUtils.deleteFile(file.getFileUrl());
                        log.info("已删除 OSS 文件，fileId={}, url={}", file.getId(), file.getFileUrl());
                    }
                } catch (Exception e) {
                    log.warn("删除 OSS 文件失败，fileId={}", file.getId(), e);
                }
            }

            // 6. 删除 MySQL 中的子表数据（按依赖顺序）
            // 6.1 删除 rag_references
            ragReferenceMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RagReference>()
                            .eq(RagReference::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 6.2 删除 knowledge_chunks
            knowledgeChunkMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 6.3 删除 knowledge_documents
            knowledgeDocumentMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 6.4 删除 knowledge_file_jobs
            knowledgeFileJobMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFileJob>()
                            .eq(KnowledgeFileJob::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 6.5 删除 knowledge_files
            knowledgeFileMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                            .eq(KnowledgeFile::getKnowledgeBaseId, knowledgeBaseId)
            );

            // 7. 软删除 knowledge_bases（更新状态为 deleted）
            existingKb.setStatus("deleted");
            existingKb.setUpdatedAt(LocalDateTime.now());
            this.updateById(existingKb);

            log.info("知识库删除成功，knowledgeBaseId={}", knowledgeBaseId);

        } catch (Exception e) {
            log.error("删除知识库失败，knowledgeBaseId={}", knowledgeBaseId, e);
            throw new RuntimeException("删除知识库失败: " + e.getMessage(), e);
        }
    }

    @Override
    public KnowledgeBaseStatsVO getKnowledgeBaseStats(Long currentUserId) {
        KnowledgeBaseStatsVO stats = new KnowledgeBaseStatsVO();

        // 统计知识库数量（排除已删除的）
        long knowledgeBaseCount = this.lambdaQuery()
                .eq(KnowledgeBase::getUserId, currentUserId)
                .ne(KnowledgeBase::getStatus, "deleted")
                .count();
        stats.setKnowledgeBaseCount((int) knowledgeBaseCount);

        // 统计文件数量（排除已删除的）
        long fileCount = knowledgeFileMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getUserId, currentUserId)
                        .ne(KnowledgeFile::getStatus, "deleted")
        );
        stats.setFileCount((int) fileCount);

        // 统计知识切片数量（排除已删除的）
        long chunkCount = knowledgeChunkMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getUserId, currentUserId)
                        .ne(KnowledgeChunk::getStatus, "deleted")
        );
        stats.setChunkCount((int) chunkCount);

        return stats;
    }

    @Override
    public KnowledgeBaseDetailStatsVO getKnowledgeBaseDetailStats(Long knowledgeBaseId, Long currentUserId) {
        // 校验知识库是否存在且属于当前用户
        KnowledgeBase kb = this.lambdaQuery()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, currentUserId)
                .ne(KnowledgeBase::getStatus, "deleted")
                .one();
        if (kb == null) {
            throw new RuntimeException("知识库不存在或无权访问");
        }

        KnowledgeBaseDetailStatsVO stats = new KnowledgeBaseDetailStatsVO();

        // 总文件数（使用冗余字段）
        stats.setFileCount(kb.getFileCount() != null ? kb.getFileCount() : 0);

        // 总切片数（使用冗余字段）
        stats.setChunkCount(kb.getChunkCount() != null ? kb.getChunkCount() : 0);

        // 向量化完成文件数（embedding_status = 'success'）
        long embeddedCount = knowledgeFileMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeFile::getEmbeddingStatus, "success")
                        .ne(KnowledgeFile::getStatus, "deleted")
        );
        stats.setEmbeddedFileCount((int) embeddedCount);

        return stats;
    }
}
