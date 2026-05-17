package com.smartorder.edu.common.rag;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.common.mapper.KnowledgeChunkMapper;
import com.smartorder.edu.common.mapper.KnowledgeDocumentMapper;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.entity.KnowledgeDocument;
import com.smartorder.edu.common.model.rag.RagChildChunk;
import com.smartorder.edu.common.model.rag.RagParseResult;
import com.smartorder.edu.common.util.TextHashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.smartorder.edu.common.common.Constants.ACTIVE;
import static com.smartorder.edu.common.common.Constants.PENDING;

@Service
@RequiredArgsConstructor
public class RagPersistService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    @Transactional
    public PersistResult saveParseResult(RagParseResult result) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUserId(result.getUserId());
        document.setKnowledgeBaseId(result.getKnowledgeBaseId());
        document.setFileId(result.getFileId());
        document.setTitle(result.getFileName());
        document.setSourceType("file");
        document.setSummary(result.getDocumentSummary());
        document.setWordCount(result.getRawText() == null ? 0 : result.getRawText().length());
        document.setChunkCount(result.getChildChunks() == null ? 0 : result.getChildChunks().size());
        document.setMetadata(JSON.toJSONString(new java.util.LinkedHashMap<String, Object>() {{
            put("fileType", result.getFileType());
            put("parserVersion", "rag-parser-v1");
            put("markdownGenerated", true);
        }}));
        document.setVersion(1);
        document.setParseVersion(1);
        document.setStatus(ACTIVE);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(document);

        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        for (RagChildChunk child : result.getChildChunks()) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setUserId(result.getUserId());
            chunk.setKnowledgeBaseId(result.getKnowledgeBaseId());
            chunk.setFileId(result.getFileId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setChunkType(child.getChunkType() == null ? "text" : child.getChunkType());
            chunk.setContent(child.getContent());
            chunk.setContentPreview(TextHashUtils.preview(child.getContent(), 500));
            chunk.setContentHash(TextHashUtils.sha256(child.getContent()));
            chunk.setTokenCount(estimateTokenCount(child.getContent()));
            chunk.setCharCount(child.getContent() == null ? 0 : child.getContent().length());
            chunk.setPageNumber(child.getPageNumber());
            chunk.setSectionTitle(child.getTitle());
            chunk.setSectionLevel(child.getSectionLevel());
            chunk.setTitlePath(child.getHeadingPath());
            chunk.setVectorCollection("rag_chunks");
            chunk.setVectorId("chunk_tmp_" + child.getChunkKey());
            chunk.setEmbeddingModel("bge-m3");
            chunk.setEmbeddingStatus(PENDING);
            chunk.setMetadata(JSON.toJSONString(child.getMetadata()));
            chunk.setStatus(ACTIVE);
            chunk.setCreatedAt(LocalDateTime.now());
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkMapper.insert(chunk);

            // 插入后用数据库ID生成稳定 vectorId，避免后续关联混乱。
            chunk.setVectorId("chunk_" + chunk.getId());
            chunkMapper.updateById(chunk);
            chunks.add(chunk);
            index++;
        }

        // 设置前后 chunk 关系
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk current = chunks.get(i);
            if (i > 0) current.setPrevChunkId(chunks.get(i - 1).getId());
            if (i < chunks.size() - 1) current.setNextChunkId(chunks.get(i + 1).getId());
            chunkMapper.updateById(current);
        }

        return new PersistResult(document, chunks);
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 2);
    }

    public record PersistResult(KnowledgeDocument document, List<KnowledgeChunk> chunks) {}
}
