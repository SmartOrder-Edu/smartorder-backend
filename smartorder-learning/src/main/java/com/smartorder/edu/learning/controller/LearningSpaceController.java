package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.dto.LearningSpaceDTO;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.impl.LearningSpaceServiceImpl;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学习空间控制器
 * @author 谢光湘
 * @since 2026/5/12
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/learning-spaces")
@Tag(name = "学习空间管理", description = "学习空间的创建、查询、更新、删除接口")
public class LearningSpaceController {

    private final LearningSpaceServiceImpl learningSpaceService;

    /**
     * 创建学习空间
     * @param dto 学习空间数据
     * @return 创建后的学习空间
     */
    @PostMapping
    @Operation(summary = "创建学习空间", description = "创建一个新的学习空间")
    public Result<LearningSpaceVO> create(@RequestBody LearningSpaceDTO dto) {
        Long userId = getCurrentUserId();
        LearningSpaceVO vo = learningSpaceService.createSpace(userId, dto);
        return Result.success(vo);
    }

    /**
     * 获取当前用户的学习空间列表
     * @param status 状态过滤（可选）
     * @return 学习空间列表
     */
    @GetMapping
    @Operation(summary = "获取学习空间列表", description = "获取当前用户的所有学习空间，可按状态过滤")
    public Result<List<LearningSpaceVO>> list(@RequestParam(required = false) String status) {
        Long userId = getCurrentUserId();
        List<LearningSpaceVO> list = learningSpaceService.listSpaces(userId, status);
        return Result.success(list);
    }

    /**
     * 获取单个学习空间详情
     * @param id 空间ID
     * @return 学习空间详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取学习空间详情", description = "根据空间ID获取学习空间详情")
    public Result<LearningSpaceVO> detail(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        LearningSpaceVO vo = learningSpaceService.getSpaceById(userId, id);
        return Result.success(vo);
    }

    /**
     * 更新学习空间
     * @param id 空间ID
     * @param dto 更新数据
     * @return 更新后的学习空间
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新学习空间", description = "更新指定学习空间的信息")
    public Result<LearningSpaceVO> update(@PathVariable Long id, @RequestBody LearningSpaceDTO dto) {
        Long userId = getCurrentUserId();
        LearningSpaceVO vo = learningSpaceService.updateSpace(userId, id, dto);
        return Result.success(vo);
    }

    /**
     * 删除学习空间（逻辑删除）
     * @param id 空间ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除学习空间", description = "逻辑删除指定的学习空间")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        learningSpaceService.deleteSpace(userId, id);
        return Result.success("学习空间删除成功");
    }

    /**
     * 从ThreadLocal中获取当前用户ID
     */
    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
