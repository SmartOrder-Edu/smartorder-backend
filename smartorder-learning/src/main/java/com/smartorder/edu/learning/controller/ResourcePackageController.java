package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;
import com.smartorder.edu.learning.service.IResourcePackageService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/learning-spaces/{spaceId}/resource-package")
@Tag(name = "学习资料包", description = "学习空间内由 Resource Agent 规划和生成资料包")
public class ResourcePackageController {

    private final IResourcePackageService resourcePackageService;

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

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
