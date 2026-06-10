package com.smartorder.edu.common.listener;

import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.model.dto.KnowledgeFileCreateResult;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.service.KnowledgeFileCreateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileListener {

    private final KnowledgeFileCreateService knowledgeFileCreateService;


    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_DB_SAVE)
    public void handleFileDbSave(KnowledgeFileDTO msgDTO) throws Exception {
        try {
            log.info("收到文件入库消息: fileHash={}", msgDTO.getFileHash());
            KnowledgeFile file = saveFileAndCreateJob(msgDTO);
            log.info("文件入库并创建处理任务完成: fileId={}, fileHash={}", file.getId(), file.getFileHash());
        } catch (Exception e) {
            log.error("处理文件入库消息异常: {}", msgDTO, e);
            throw e;
        }
    }

    public KnowledgeFile saveFileAndCreateJob(KnowledgeFileDTO msg) {
        KnowledgeFileCreateResult result = knowledgeFileCreateService.createFileAndJob(msg);
        return result.getFile();
    }
}
