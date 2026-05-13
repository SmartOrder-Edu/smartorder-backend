package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;
import com.smartorder.edu.student.service.IFileFolderService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotNull;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/folders")
@Tag(name = "文件夹管理", description = "文件夹的增删改查接口")
public class FileFolderController {

    private final IFileFolderService fileFolderService;

    // todo
    @GetMapping("/tree")
    @Operation(summary = "获取文件夹树", description = "获取当前用户的文件夹树形结构")
    public Result<List<FileFolderContentVO>> getTree() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        List<FileFolderContentVO> tree = fileFolderService.getFolderTree(userId);
        return Result.success(tree);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件夹详情", description = "根据文件夹ID获取文件夹信息")
    public Result<FileFolderContentVO> getById(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO vo = fileFolderService.getFolderById(id,userId);
        return Result.success(vo);
    }

    @PostMapping
    @Operation(summary = "创建文件夹", description = "创建新文件夹")
    public Result<FileFolderContentVO> create(@Validated @RequestBody FileFolderDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO vo = fileFolderService.createFolder(dto, userId);
        return Result.success(vo);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新文件夹", description = "重命名文件夹")
    public Result<FileFolderContentVO> update(@PathVariable Long id, @NotNull(message = "文件夹名称不能为空") @RequestParam String folderName) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO vo = fileFolderService.updateFolder(id, folderName, userId);
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

    @PostMapping("/move/file")
    @Operation(summary = "移动文件", description = "将文件移动到指定文件夹")
    public Result<String> moveFile(@RequestParam Long fileId,
                                   @RequestParam Long targetFolderId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.moveFile(fileId, targetFolderId, userId);
        return Result.success("文件移动成功");
    }

    @PostMapping("/move/folder")
    @Operation(summary = "移动文件夹", description = "将文件夹移动到指定父文件夹")
    public Result<String> moveFolder(@RequestParam Long id,
                                     @RequestParam Long targetParentId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.moveFolder(id, targetParentId, userId);
        return Result.success("文件夹移动成功");
    }
}
