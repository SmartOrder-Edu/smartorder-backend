package com.smartorder.edu.student.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.FileResourceDTO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.service.IFileResourceService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/files")
@Tag(name = "文件资源管理", description = "文件资源的增删改查接口")
public class FileResourceController {

    private final IFileResourceService fileResourceService;

    @GetMapping
    @Operation(summary = "获取文件列表", description = "按文件夹分页获取当前用户的文件列表")
    public Result<Page<FileResourceVO>> list(@RequestParam(required = false) Long folderId,
                                              @RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "20") Integer pageSize) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        Page<FileResourceVO> page = fileResourceService.getFilesByFolder(folderId, userId, pageNum, pageSize);
        return Result.success(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件详情", description = "根据文件ID获取文件信息")
    public Result<FileResourceVO> getById(@PathVariable Long id) {
        FileResourceVO vo = fileResourceService.getFileById(id);
        return Result.success(vo);
    }

    @PostMapping
    @Operation(summary = "创建文件记录", description = "创建文件资源记录")
    public Result<FileResourceVO> create(@RequestBody FileResourceDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileResourceVO vo = fileResourceService.createFile(dto, userId);
        return Result.success(vo);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件", description = "删除文件资源记录")
    public Result<String> delete(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.deleteFile(id, userId);
        return Result.success("文件删除成功");
    }

    @PutMapping("/{id}/parse-status")
    @Operation(summary = "更新解析状态", description = "更新文件的解析状态，0-未解析，1-解析中，2-向量化完成，3-解析失败")
    public Result<String> updateParseStatus(@PathVariable Long id, @RequestParam Integer parseStatus) {
        fileResourceService.updateParseStatus(id, parseStatus);
        return Result.success("解析状态更新成功");
    }
}
