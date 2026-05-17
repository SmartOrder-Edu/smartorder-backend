package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;

import com.smartorder.edu.student.domain.dto.InitFileUploadDTO;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.service.IFileFolderService;
import com.smartorder.edu.student.service.IFileResourceService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/yun-resource")
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
    public Result<String> createFolder(@Validated @RequestBody FileFolderDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.createFolder(dto, userId);
        return Result.success("文件夹创建成功");
    }

    @PutMapping("/folders/{id}")
    @Operation(summary = "更新文件夹", description = "重命名文件夹")
    public Result<String> updateFolder(@PathVariable Long id, @NotNull(message = "文件夹名称不能为空") @RequestParam String folderName) {
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
    @DeleteMapping("/file/{id}")
    @Operation(summary = "删除文件", description = "删除文件资源")
    public Result<String> deleteFile(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.deleteFile(id, userId);
        return Result.success("文件删除成功");
    }

    @GetMapping("/file/{id}")
    @Operation(summary = "获取文件详情", description = "根据文件ID获取文件信息")
    public Result<FileResourceVO> getFileById(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileResourceVO vo = fileResourceService.getFileByIdAndUserId(id,userId);
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

    //todo
    @PutMapping("/file/{id}/parse-status")
    @Operation(summary = "更新解析状态", description = "更新文件的解析状态，0-未解析，1-解析中，2-向量化完成，3-解析失败")
    public Result<String> updateFileParseStatus(@PathVariable Long id, @RequestParam Integer parseStatus) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.updateParseStatus(id, parseStatus);
        return Result.success("解析状态更新成功");
    }

    // 阿里云oss文件分片
    @PostMapping("/file/init")
    public Result<Map<String, Object>> initUpload(@RequestBody InitFileUploadDTO dto) {
        log.info("【云盘】初始化OSS分片上传, dto={}", dto);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        Map<String, Object> result = fileResourceService.initMultipartUpload(dto, userId);
        return Result.success(result,"初始化上传任务成功");
    }

    @GetMapping("/file/sign")
    public Result<String> getUploadSign(@RequestParam String fileHash,@RequestParam int partNumber) {
        log.info("【云盘】获取分片上传预签名URL, fileHash={}, partNumber={}", fileHash, partNumber);
        try {
            Claims claims = ThreadLocalUtils.get();
            Long userId = claims.get("userId", Long.class);
            String url = fileResourceService.getMultipartUploadSignUrl(fileHash,partNumber,userId);
            return Result.success(url,"获取分片上传预签名URL成功");
        }catch (Exception e){
            return Result.fail(ResultCode.UPLOAD_TASK_NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/file/chunk")
    public Result<String> uploadChunk(@RequestParam String fileHash,
                                  @RequestParam int chunkNumber,
                                  @RequestParam String eTag) {
        log.info("【云盘】通知分片上传完成, fileHash={}, chunkNumber={}, eTag={}", fileHash, chunkNumber, eTag);
        try {
            Claims claims = ThreadLocalUtils.get();
            Long userId = claims.get("userId", Long.class);
            fileResourceService.notifyChunkUploadDone(fileHash, chunkNumber, eTag,userId);
            return Result.success("分片上传成功");
        }catch (Exception e){
            return Result.fail(ResultCode.UPLOAD_TASK_NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/file/merge")
    public Result<Map<String, Object>> mergeChunks(@RequestParam String fileHash, @RequestParam Long fileId) {
        log.info("【云盘】合并分片, fileHash={}, fileId={}", fileHash, fileId);
        try {
            Claims claims = ThreadLocalUtils.get();
            Long userId = claims.get("userId", Long.class);
            Map<String, Object> result = fileResourceService.mergeChunks(fileHash, fileId, userId);
            return Result.success(result, "文件合并成功");
        } catch (Exception e) {
            return Result.fail(ResultCode.MERGE_CHUNK_ERROR, e.getMessage());
        }
    }

    @PostMapping("/file/cancel")
    public Result<String> cancelUpload(@RequestParam String fileHash) {
        log.info("【云盘】取消文件上传, filehash={}", fileHash);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.cancelUpload(fileHash, userId);
        return Result.success("文件上传取消成功");
    }

    @GetMapping("/download/{id}")
    @Operation(summary = "下载文件", description = "根据文件ID下载文件")
    public void downloadFile(@PathVariable Long id, HttpServletResponse response) throws IOException {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);

        IFileResourceService.FileDownloadResult result = fileResourceService.downloadFile(id, userId);
        InputStream inputStream = null;
        try {
            inputStream = result.inputStream();

            response.reset();
            response.setContentType("application/octet-stream");
            response.setCharacterEncoding("UTF-8");

            String fileName = result.fileName() != null ? result.fileName() : "未命名文件";
            String fileType = result.fileType();
            if (fileType != null) {
                String extension = "." + fileType.toLowerCase();
                if (!fileName.endsWith(extension)) {
                    fileName += extension;
                }
            }
            String encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);
            response.setHeader("Content-Length", String.valueOf(result.fileSize()));
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition, Content-Length");

            byte[] buffer = new byte[8192];
            int len;
            OutputStream outputStream = response.getOutputStream();
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.flush();
        } catch (Exception e) {
            log.error("文件下载失败, id={}", id, e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"文件下载失败\"}");
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
