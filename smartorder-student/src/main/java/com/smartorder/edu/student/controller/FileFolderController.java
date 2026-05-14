package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.dto.FileResourceDTO;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.service.IFileFolderService;
import com.smartorder.edu.student.service.IFileResourceService;
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
@RequestMapping("/yun")
@Tag(name = "文件夹管理", description = "文件夹的增删改查接口")
public class FileFolderController {

    private final IFileFolderService fileFolderService;

    private final IFileResourceService fileResourceService;

    // 文件夹
    // todo
    @GetMapping("/folders/tree")
    @Operation(summary = "获取文件夹树", description = "获取当前用户的文件夹树形结构")
    public Result<List<FileFolderContentVO>> getTree() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        List<FileFolderContentVO> tree = fileFolderService.getFolderTree(userId);
        return Result.success(tree);
    }

    @GetMapping("/folders/{id}")
    @Operation(summary = "获取文件夹详情", description = "根据文件夹ID获取文件夹信息")
    public Result<FileFolderContentVO> getFolderById(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO vo = fileFolderService.getFolderById(id,userId);
        return Result.success(vo);
    }

    @PostMapping("/folders")
    @Operation(summary = "创建文件夹", description = "创建新文件夹")
    public Result<FileFolderContentVO> createFolder(@Validated @RequestBody FileFolderDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.createFolder(dto, userId);
        return Result.success("文件夹创建成功");
    }

    @PutMapping("/folders/{id}")
    @Operation(summary = "更新文件夹", description = "重命名文件夹")
    public Result<FileFolderContentVO> updateFolder(@PathVariable Long id, @NotNull(message = "文件夹名称不能为空") @RequestParam String folderName) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.updateFolder(id, folderName, userId);
        return Result.success("文件夹重命名成功");
    }

    @DeleteMapping("/folders/{id}")
    @Operation(summary = "删除文件夹", description = "删除文件夹，有子文件夹时不允许删除")
    public Result<String> deleteFolder(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.deleteFolder(id, userId);
        return Result.success("文件夹删除成功");
    }

    @PostMapping("/folders/move")
    @Operation(summary = "移动文件夹", description = "将文件夹移动到指定父文件夹")
    public Result<String> moveFolder(@RequestParam Long id,
                                     @RequestParam Long targetParentId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.moveFolder(id, targetParentId, userId);
        return Result.success("文件夹移动成功");
    }

    // 文件资源
    @PostMapping("file")
    @Operation(summary = "创建文件记录", description = "创建文件资源记录")
    public Result<FileResourceVO> createFile(@RequestBody FileResourceDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.createFile(dto, userId);
        return Result.success("文件记录创建成功");
    }

    @DeleteMapping("/file/{id}")
    @Operation(summary = "删除文件资源", description = "删除文件资源")
    public Result<String> deleteFile(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.deleteFile(id, userId);
        return Result.success("文件夹删除成功");
    }

    @GetMapping("/file/{id}")
    @Operation(summary = "获取文件详情", description = "根据文件ID获取文件信息")
    public Result<FileResourceVO> getFileById(@PathVariable Long id) {
        FileResourceVO vo = fileResourceService.getFileById(id);
        return Result.success(vo);
    }

    @PostMapping("/file/move")
    @Operation(summary = "移动文件", description = "将文件移动到指定文件夹")
    public Result<String> moveFile(@RequestParam Long fileId,
                                   @RequestParam Long targetFolderId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.moveFile(fileId, targetFolderId, userId);
        return Result.success("文件移动成功");
    }

    @PutMapping("/{id}/parse-status")
    @Operation(summary = "更新解析状态", description = "更新文件的解析状态，0-未解析，1-解析中，2-向量化完成，3-解析失败")
    public Result<String> updateFileParseStatus(@PathVariable Long id, @RequestParam Integer parseStatus) {
        fileResourceService.updateParseStatus(id, parseStatus);
        return Result.success("解析状态更新成功");
    }
}
