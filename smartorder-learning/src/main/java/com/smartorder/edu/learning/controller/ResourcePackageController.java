package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import com.smartorder.edu.learning.domain.vo.ResourceGenerationJobVO;
import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import com.smartorder.edu.learning.service.IResourcePackageService;
import com.smartorder.edu.learning.service.IResourceGenerationJobService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/learning-spaces/{spaceId}/resource-package")
@Tag(name = "学习资料包", description = "学习空间内由 Resource Agent 规划和生成资料包")
public class ResourcePackageController {

    private final IResourcePackageService resourcePackageService;
    private final IResourceGenerationJobService resourceGenerationJobService;
    private final ILearningSpaceService learningSpaceService;

    @PostMapping("/preview")
    @Operation(summary = "预览资料包方案", description = "根据学习空间、任务、知识点生成待确认的资料包方案，不落库")
    public Result<ResourcePackageVO> preview(
            @PathVariable Long spaceId,
            @RequestBody(required = false) ResourcePackageGenerateDTO dto
    ) {
        Long userId = getCurrentUserId();
        return Result.success(resourcePackageService.previewPackage(userId, spaceId, dto));
    }

    @PostMapping("/generate")
    @Operation(summary = "确认生成资料包", description = "确认资料包方案并生成资源记录，资源会绑定到空间、任务和知识点")
    public Result<ResourcePackageVO> generate(
            @PathVariable Long spaceId,
            @RequestBody(required = false) ResourcePackageGenerateDTO dto
    ) {
        Long userId = getCurrentUserId();
        return Result.success(resourcePackageService.generatePackage(userId, spaceId, dto));
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式生成资料包", description = "确认资料包方案并通过 SSE 实时返回资源生成过程和内容片段")
    public SseEmitter generateStream(
            @PathVariable Long spaceId,
            @RequestBody(required = false) ResourcePackageGenerateDTO dto
    ) {
        Long userId = getCurrentUserId();
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onCompletion(() -> log.info("[ResourcePackageStream] completed user={} space={}", userId, spaceId));
        emitter.onTimeout(() -> {
            log.warn("[ResourcePackageStream] timeout user={} space={}", userId, spaceId);
            emitter.complete();
        });
        emitter.onError(ex -> log.warn("[ResourcePackageStream] error user={} space={} reason={}",
                userId, spaceId, ex.getMessage()));
        resourcePackageService.streamGeneratePackage(userId, spaceId, dto, emitter);
        return emitter;
    }

    @GetMapping("/jobs")
    @Operation(summary = "查询资料包资源生成任务", description = "返回学习空间内最近的资源生成任务，用于前端轮询进度")
    public Result<java.util.List<ResourceGenerationJobVO>> jobs(
            @PathVariable Long spaceId,
            @RequestParam(defaultValue = "20") Integer limit
    ) {
        Long userId = getCurrentUserId();
        learningSpaceService.getSpaceById(userId, spaceId);
        return Result.success(resourceGenerationJobService.getRecentBySpaceId(spaceId, limit).stream()
                .map(this::toJobVO)
                .toList());
    }

    private ResourceGenerationJobVO toJobVO(ResourceGenerationJob job) {
        ResourceGenerationJobVO vo = new ResourceGenerationJobVO();
        vo.setId(job.getId());
        vo.setSpaceId(job.getSpaceId());
        vo.setResourceId(job.getResourceId());
        vo.setPackageId(job.getPackageId());
        vo.setResourceType(job.getResourceType());
        vo.setTitle(job.getTitle());
        vo.setReason(job.getReason());
        vo.setStatus(job.getStatus());
        vo.setRetryCount(job.getRetryCount());
        vo.setErrorMessage(job.getErrorMessage());
        vo.setTraceId(job.getTraceId());
        vo.setCreateTime(job.getCreateTime());
        vo.setUpdateTime(job.getUpdateTime());
        return vo;
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
