package com.smartorder.edu.common.rag;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.common.mapper.KnowledgeChunkMapper;
import com.smartorder.edu.common.mapper.KnowledgeDocumentMapper;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.entity.KnowledgeDocument;
import com.smartorder.edu.common.model.rag.RagChildChunk;
import com.smartorder.edu.common.model.rag.RagParentChunk;
import com.smartorder.edu.common.model.rag.RagParseResult;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.util.TextHashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.smartorder.edu.common.common.Constants.ACTIVE;
import static com.smartorder.edu.common.common.Constants.PENDING;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagPersistService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final AliOSSUtils aliOSSUtils;

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

        // 上传 Markdown 到 OSS 并保存路径
        if (result.getMarkdown() != null && !result.getMarkdown().isEmpty()) {
            String markdownPath = uploadMarkdownToOss(
                    result.getKnowledgeBaseId(),
                    result.getFileId(),
                    result.getMarkdown()
            );
            document.setRawTextPath(markdownPath);
        }

        documentMapper.insert(document);

        // 保存父块，建立 parentChunkKey -> 数据库ID 映射
        Map<String, Long> parentKeyToIdMap = new HashMap<>();
        if (result.getParentChunks() != null) {
            for (RagParentChunk parent : result.getParentChunks()) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setUserId(result.getUserId());
                chunk.setKnowledgeBaseId(result.getKnowledgeBaseId());
                chunk.setFileId(result.getFileId());
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(parent.getChunkIndex());
                chunk.setChunkType("parent");
                chunk.setContent(parent.getContent());
                chunk.setContentPreview(TextHashUtils.preview(parent.getContent(), 500));
                chunk.setContentHash(TextHashUtils.sha256(parent.getContent()));
                chunk.setTitlePath(parent.getHeadingPath());
                chunk.setSectionTitle(parent.getTitle());
                chunk.setVectorCollection("rag_chunks");
                chunk.setEmbeddingStatus(PENDING);
                chunk.setStatus(ACTIVE);
                chunk.setCreatedAt(LocalDateTime.now());
                chunk.setUpdatedAt(LocalDateTime.now());
                chunkMapper.insert(chunk);
                chunk.setVectorId("chunk_" + chunk.getId());
                chunkMapper.updateById(chunk);
                parentKeyToIdMap.put(parent.getParentChunkKey(), chunk.getId());
            }
        }

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
            chunk.setPageNumber(child.getPageNumber() != null ? child.getPageNumber() : 1);
            chunk.setSectionTitle(child.getTitle());
            chunk.setSectionLevel(child.getSectionLevel());
            chunk.setTitlePath(child.getHeadingPath());
            chunk.setVectorCollection("rag_chunks");
            chunk.setVectorId("chunk_tmp_" + child.getChunkKey());
            chunk.setEmbeddingModel("bge-m3");
            chunk.setEmbeddingStatus(PENDING);
            chunk.setMetadata(JSON.toJSONString(child.getMetadata()));
            chunk.setParentChunkId(parentKeyToIdMap.get(child.getParentChunkKey()));
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

    /**
     * 上传 Markdown 内容到 OSS
     * @param knowledgeBaseId 知识库ID
     * @param fileId 文件ID
     * @param markdown Markdown 内容
     * @return OSS 文件访问路径
     */
    private String uploadMarkdownToOss(Long knowledgeBaseId, Long fileId, String markdown) {
        String filePath = "knowledge-markdown/" + knowledgeBaseId + "/";
        String fileName = fileId + ".md";
        try (InputStream inputStream = new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))) {
            String url = aliOSSUtils.uploadStream(inputStream, fileName, filePath);
            log.info("Markdown 文件上传成功，fileId={}, url={}", fileId, url);
            return url;
        } catch (IOException e) {
            log.error("上传 Markdown 文件失败，fileId={}", fileId, e);
            throw new RuntimeException("上传 Markdown 文件失败", e);
        }
    }

    public record PersistResult(KnowledgeDocument document, List<KnowledgeChunk> chunks) {}
}
