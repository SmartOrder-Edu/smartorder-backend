package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.vo.FileFolderVO;
import com.smartorder.edu.student.service.IFileFolderService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/folders")
@Tag(name = "文件夹管理", description = "文件夹的增删改查接口")
public class FileFolderController {

    private final IFileFolderService fileFolderService;

    @GetMapping("/tree")
    @Operation(summary = "获取文件夹树", description = "获取当前用户的文件夹树形结构")
    public Result<List<FileFolderVO>> getTree() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        List<FileFolderVO> tree = fileFolderService.getFolderTree(userId);
        return Result.success(tree);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件夹详情", description = "根据文件夹ID获取文件夹信息")
    public Result<FileFolderVO> getById(@PathVariable Long id) {
        FileFolderVO vo = fileFolderService.getFolderById(id);
        return Result.success(vo);
    }

    @PostMapping
    @Operation(summary = "创建文件夹", description = "创建新文件夹")
    public Result<FileFolderVO> create(@RequestBody FileFolderDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderVO vo = fileFolderService.createFolder(dto, userId);
        return Result.success(vo);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新文件夹", description = "重命名文件夹")
    public Result<FileFolderVO> update(@PathVariable Long id, @RequestParam String folderName) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderVO vo = fileFolderService.updateFolder(id, folderName, userId);
        return Result.success(vo);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件夹", description = "删除文件夹，有子文件夹时不允许删除")
    public Result<String> delete(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.deleteFolder(id, userId);
        return Result.success("文件夹删除成功");
    }
}
