package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.vo.LessonBundleStatusVO;
import com.smartorder.edu.learning.dto.LessonBundleGenerateDTO;
import com.smartorder.edu.learning.dto.LessonBundleStatusBatchDTO;
import com.smartorder.edu.learning.service.ILessonBundleService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lesson-bundles")
@Tag(name = "学习脚本包", description = "学习脚本预生成、状态查询与预热接口")
public class LessonBundleController {

    private final ILessonBundleService lessonBundleService;

    @PostMapping("/generate")
    @Operation(summary = "生成学习脚本包", description = "支持任务或学习路径节点维度的手动预生成")
    public Result<LessonBundleStatusVO> generate(@RequestBody LessonBundleGenerateDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(lessonBundleService.generateBundle(userId, dto));
    }

    @GetMapping("/by-target")
    @Operation(summary = "查询学习脚本包状态", description = "按任务或学习路径节点查询最近一次脚本生成状态")
    public Result<LessonBundleStatusVO> byTarget(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Long pathNodeId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String mode
    ) {
        Long userId = getCurrentUserId();
        return Result.success(lessonBundleService.getBundleStatus(userId, taskId, pathNodeId, spaceId, mode));
    }

    @PostMapping("/by-targets")
    @Operation(summary = "批量查询学习脚本包状态", description = "按多个学习路径节点一次性查询最近一次脚本生成状态")
    public Result<List<LessonBundleStatusVO>> byTargets(@RequestBody LessonBundleStatusBatchDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(lessonBundleService.getBundleStatuses(userId, dto));
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
