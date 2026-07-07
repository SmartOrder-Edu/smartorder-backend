package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.entity.FileParseRecord;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.rag.MarkdownChunkResult;
import com.smartorder.edu.common.model.rag.RagParseResult;
import com.smartorder.edu.common.util.UnifiedMultimodalParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Service
@RequiredArgsConstructor
public class RagFileParserService {

    private final UnifiedMultimodalParser multimodalParser;
    private final MarkdownNormalizeService markdownNormalizeService;
    private final MarkdownChunkService markdownChunkService;
    private final ExcelRagParser excelRagParser;
    private final PdfRagParser pdfRagParser;
    private final FileLoadService fileLoadService;

    public RagParseResult parseForRag(KnowledgeFile fileEntity) throws Exception {
        File localFile = fileLoadService.loadToLocalTempFile(fileEntity);

        try {
            String fileType = normalizeFileType(fileEntity);
            String fileName = fileEntity.getOriginalName();
            if (isExcel(fileType, fileName)) {
                return excelRagParser.parse(localFile, fileEntity);
            }
            if (isPdf(fileType, fileName)) {
                return pdfRagParser.parse(localFile, fileEntity);
            }

            String rawText;
            Integer pageCount = 1;
            if (isMarkdown(fileType, fileName)) {
                rawText = Files.readString(localFile.toPath(), StandardCharsets.UTF_8);
            } else {
                FileParseRecord record = multimodalParser.parse(localFile);
                rawText = record.getRawContent();
                if (record.getPageCount() != null) {
                    pageCount = record.getPageCount();
                }
            }

            if (rawText == null || rawText.trim().isEmpty()) {
                throw new RuntimeException("No valid text parsed from file: " + fileName);
            }

            String markdown = markdownNormalizeService.toMarkdown(rawText, fileType, fileName);

            MarkdownChunkResult chunkResult = markdownChunkService.split(
                    markdown,
                    fileEntity.getId(),
                    fileEntity.getKnowledgeBaseId(),
                    fileName,
                    fileType,
                    pageCount
            );

            return RagParseResult.builder()
                    .fileId(fileEntity.getId())
                    .userId(fileEntity.getUserId())
                    .knowledgeBaseId(fileEntity.getKnowledgeBaseId())
                    .fileName(fileName)
                    .fileType(fileType)
                    .rawText(rawText)
                    .markdown(markdown)
                    .documentSummary(chunkResult.getDocumentSummary())
                    .pageCount(pageCount)
                    .parentChunks(chunkResult.getParentChunks())
                    .childChunks(chunkResult.getChildChunks())
                    .build();
        } finally {
            if (localFile != null && localFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                localFile.delete();
            }
        }
    }

    private String normalizeFileType(KnowledgeFile file) {
        if (file.getMimeType() != null && !file.getMimeType().isBlank()) {
            return file.getMimeType();
        }
        String name = file.getOriginalName() == null ? "" : file.getOriginalName().toLowerCase();
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (name.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        return "text/plain";
    }

    private boolean isExcel(String fileType, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        return fileType.contains("spreadsheet") || lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private boolean isPdf(String fileType, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        return fileType.contains("pdf") || lower.endsWith(".pdf");
    }

    private boolean isMarkdown(String fileType, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        String type = fileType == null ? "" : fileType.toLowerCase();
        return type.contains("markdown") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }
}
