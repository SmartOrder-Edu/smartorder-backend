package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.rag.RagChildChunk;
import com.smartorder.edu.common.model.rag.RagParentChunk;
import com.smartorder.edu.common.model.rag.RagParseResult;
import com.smartorder.edu.common.util.UnifiedMultimodalParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * PDF 文件 RAG 解析器
 *
 * 设计思想：
 * 1. 整个 PDF 文件作为一个父块 ParentChunk
 * 2. 每页作为一个子块 ChildChunk
 * 3. 检测到图片或文本量少的页面进行 OCR 识别
 * 4. 每页内容转为 Markdown 格式
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PdfRagParser {

    private final UnifiedMultimodalParser multimodalParser;
    private final MarkdownNormalizeService markdownNormalizeService;

    /**
     * 文本长度阈值：低于此值认为文本量少，需要 OCR
     */
    private static final int TEXT_LENGTH_THRESHOLD = 30;

    /**
     * 解析 PDF 文件，生成 RAG 解析结果
     *
     * @param file       实际上传后的 PDF 文件
     * @param fileEntity 数据库中的知识库文件实体
     * @return RagParseResult，包含 markdown、父块、子块等数据
     */
    public RagParseResult parse(File file, KnowledgeFile fileEntity) throws Exception {
        String parentKey = UUID.randomUUID().toString();
        String fileName = fileEntity.getOriginalName();
        List<RagParentChunk> parents = new ArrayList<>();
        List<RagChildChunk> children = new ArrayList<>();
        StringBuilder wholeMarkdown = new StringBuilder();

        try (PDDocument document = PDDocument.load(file)) {
            if (document.isEncrypted()) {
                throw new Exception("PDF文件已加密，无法解析");
            }

            int totalPages = document.getNumberOfPages();

            // 1. 创建父块
            RagParentChunk parent = RagParentChunk.builder()
                    .parentChunkKey(parentKey)
                    .title(fileName)
                    .headingPath(fileName)
                    .content("PDF 文件：" + fileName)
                    .summary("PDF 文件，共 " + totalPages + " 页")
                    .chunkIndex(0)
                    .sourceLocator("pdf")
                    .metadata(Map.of(
                            "sourceType", "pdf",
                            "fileId", fileEntity.getId(),
                            "knowledgeBaseId", fileEntity.getKnowledgeBaseId(),
                            "chunkRole", "parent"
                    ))
                    .build();
            parents.add(parent);

            // 2. 遍历每一页
            int childIndex = 0;
            for (int i = 0; i < totalPages; i++) {
                try {
                    String pageText = multimodalParser.extractPdfPageText(file, i);
                    boolean hasImages = multimodalParser.hasImagesInPage(file, i);
                    String contentType;

                    // 检测到图片 或 文本量少 → OCR
                    if (hasImages || pageText == null || pageText.length() < TEXT_LENGTH_THRESHOLD) {
                        log.info("PDF第{}页{}，进行OCR识别", i + 1, hasImages ? "检测到图片" : "文本量少");
                        String ocrText = multimodalParser.ocrPdfPage(file, i);
                        pageText = (ocrText == null || ocrText.isBlank())
                                ? "本页未识别到有效文字。"
                                : ocrText;
                        contentType = "ocr";
                    } else {
                        contentType = "text";
                    }

                    // 转 Markdown
                    String pageMarkdown = markdownNormalizeService.toMarkdown(
                            pageText, "application/pdf", fileName + " 第" + (i + 1) + "页"
                    );

                    wholeMarkdown.append("\n\n## 第 ").append(i + 1).append(" 页\n\n").append(pageMarkdown);

                    // 子块元信息
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("sourceType", "pdf");
                    metadata.put("fileId", fileEntity.getId());
                    metadata.put("knowledgeBaseId", fileEntity.getKnowledgeBaseId());
                    metadata.put("fileName", fileName);
                    metadata.put("parentChunkKey", parentKey);
                    metadata.put("pageNumber", i + 1);
                    metadata.put("contentType", contentType);
                    metadata.put("chunkRole", "child");

                    children.add(RagChildChunk.builder()
                            .chunkKey(UUID.randomUUID().toString())
                            .parentChunkKey(parentKey)
                            .title("第 " + (i + 1) + " 页")
                            .headingPath(fileName + "/第 " + (i + 1) + " 页")
                            .content(pageMarkdown)
                            .summary(simpleSummary(pageMarkdown, 260))
                            .keywords(Collections.emptyList())
                            .sourceType("pdf")
                            .chunkIndex(childIndex++)
                            .sectionLevel(2)
                            .pageNumber(i + 1)
                            .chunkType("ocr".equals(contentType) ? "image_ocr" : "text")
                            .metadata(metadata)
                            .build());
                } catch (Exception e) {
                    log.error("PDF第{}页解析失败，跳过该页", i + 1, e);
                }
            }
        }

        String markdown = wholeMarkdown.toString().trim();

        return RagParseResult.builder()
                .fileId(fileEntity.getId())
                .userId(fileEntity.getUserId())
                .knowledgeBaseId(fileEntity.getKnowledgeBaseId())
                .fileName(fileName)
                .fileType("pdf")
                .rawText(markdown)
                .markdown(markdown)
                .documentSummary("PDF 解析完成，共 " + children.size() + " 页")
                .parentChunks(parents)
                .childChunks(children)
                .build();
    }

    private String simpleSummary(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen) + "...";
    }
}
