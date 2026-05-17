package com.smartorder.edu.common.rag;

import com.smartorder.edu.common.model.entity.KnowledgeFile;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;

@Service
public class FileLoadService {

    /**
     * 将已入库文件加载成本地临时文件，供 PDFBox/POI/FFmpeg 等依赖本地文件的解析器使用。
     * 如果 fileUrl 是 OSS HTTP 地址，这里直接下载；如果是本地路径，则复制。
     */
    public File loadToLocalTempFile(KnowledgeFile fileEntity) throws Exception {
        String suffix = getSuffix(fileEntity.getOriginalName());
        File temp = File.createTempFile("rag_parse_", suffix);

        String fileUrl = fileEntity.getFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new RuntimeException("文件URL为空，无法解析：fileId=" + fileEntity.getId());
        }

        if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) {
            try (InputStream in = new URL(fileUrl).openStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                in.transferTo(out);
            }
        } else {
            Files.copy(new File(fileUrl).toPath(), temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        return temp;
    }

    private String getSuffix(String fileName) {
        if (fileName == null) return ".tmp";
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index) : ".tmp";
    }
}
