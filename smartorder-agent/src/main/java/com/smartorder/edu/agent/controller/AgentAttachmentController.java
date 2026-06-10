package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.dto.AttachmentUploadInitRequest;
import com.smartorder.edu.agent.model.dto.AttachmentUploadMergeRequest;
import com.smartorder.edu.agent.model.vo.AttachmentStatusVO;
import com.smartorder.edu.agent.model.vo.AttachmentUploadResponse;
import com.smartorder.edu.agent.service.AgentConversationAttachmentService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/agent/attachments")
@RequiredArgsConstructor
public class AgentAttachmentController {

    private final AgentConversationAttachmentService attachmentService;

    @PostMapping("/upload/init")
    public AttachmentUploadResponse initUpload(@Validated @RequestBody AttachmentUploadInitRequest request) {
        return attachmentService.initUpload(request, getCurrentUserId());
    }

    @PostMapping("/upload/chunk")
    public Boolean uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("partNumber") Integer partNumber,
            @RequestParam("file") MultipartFile file) {
        attachmentService.uploadPart(uploadId, partNumber, file);
        return true;
    }

    @PostMapping("/upload/merge")
    public AttachmentUploadResponse mergeUpload(@Validated @RequestBody AttachmentUploadMergeRequest request) {
        return attachmentService.mergeUpload(request, getCurrentUserId());
    }

    @GetMapping("/{conversationId}/{fileId}/status")
    public AttachmentStatusVO getStatus(@PathVariable String conversationId, @PathVariable Long fileId) {
        return attachmentService.getStatus(conversationId, fileId, getCurrentUserId());
    }

    @GetMapping("/{conversationId}")
    public List<AttachmentStatusVO> listAttachments(@PathVariable String conversationId) {
        return attachmentService.listAttachments(conversationId, getCurrentUserId());
    }

    @DeleteMapping("/{conversationId}/{fileId}")
    public String deleteAttachment(@PathVariable String conversationId, @PathVariable Long fileId) {
        attachmentService.deleteAttachment(conversationId, fileId, getCurrentUserId());
        return "附件已移除";
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
