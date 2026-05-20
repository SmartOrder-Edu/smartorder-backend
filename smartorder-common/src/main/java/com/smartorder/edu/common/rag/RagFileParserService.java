package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.entity.FileParseRecord;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.rag.MarkdownChunkResult;
import com.smartorder.edu.common.model.rag.RagParseResult;

import com.smartorder.edu.common.util.UnifiedMultimodalParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;



@Service
@RequiredArgsConstructor
public class RagFileParserService {

    private final UnifiedMultimodalParser multimodalParser;
    private final MarkdownNormalizeService markdownNormalizeService;
    private final MarkdownChunkService markdownChunkService;
    private final ExcelRagParser excelRagParser;
    private final FileLoadService fileLoadService;

    public RagParseResult parseForRag(KnowledgeFile fileEntity) throws Exception {

        File localFile = fileLoadService.loadToLocalTempFile(fileEntity);

        try {
            String fileType = normalizeFileType(fileEntity);
            if (isExcel(fileType, fileEntity.getOriginalName())) {
                return excelRagParser.parse(localFile, fileEntity);
            }

            FileParseRecord record = multimodalParser.parse(localFile);
            String rawText = record.getRawContent();
            if (rawText == null || rawText.trim().isEmpty()) {
                throw new RuntimeException("文件未解析出有效文本：" + fileEntity.getOriginalName());
            }

            String markdown = markdownNormalizeService.toMarkdown(rawText, fileType, fileEntity.getOriginalName());

            MarkdownChunkResult chunkResult = markdownChunkService.split(
                    markdown,
                    fileEntity.getId(),
                    fileEntity.getKnowledgeBaseId(),
                    fileEntity.getOriginalName(),
                    fileType
            );

            return RagParseResult.builder()
                    .fileId(fileEntity.getId())
                    .userId(fileEntity.getUserId())
                    .knowledgeBaseId(fileEntity.getKnowledgeBaseId())
                    .fileName(fileEntity.getOriginalName())
                    .fileType(fileType)
                    .rawText(rawText)
                    .markdown(markdown)
                    .documentSummary(chunkResult.getDocumentSummary())
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
        return "text/plain";
    }

    private boolean isExcel(String fileType, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        return fileType.contains("spreadsheet") || lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }
}
