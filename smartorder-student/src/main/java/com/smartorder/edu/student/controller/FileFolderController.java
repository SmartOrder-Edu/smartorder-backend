package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.dto.FolderExportTask;
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
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/yun-resource")
@Tag(name = "文件夹管理", description = "文件夹的增删改查接口")
public class FileFolderController {

    private final IFileFolderService fileFolderService;

    private final IFileResourceService fileResourceService;

    /** 获取当前用户完整文件夹树：一次全查 + 内存按 parentId 递归组树 */
    @GetMapping("/folders/tree")
    @Operation(summary = "获取文件夹树", description = "获取当前用户的文件夹树形结构")
    public Result<FileFolderContentVO> getTree() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO tree = fileFolderService.getFolderTree(userId);
        return Result.success(tree);
    }

    /** 获取单层文件夹详情：当前文件夹信息 + 子文件夹列表 + 文件列表（含归属校验） */
    @GetMapping("/folders/{id}")
    @Operation(summary = "获取文件夹详情", description = "根据文件夹ID获取文件夹信息")
    public Result<FileFolderContentVO> getFolderById(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileFolderContentVO vo = fileFolderService.getFolderById(id,userId);
        return Result.success(vo);
    }

    /** 创建文件夹：校验学生身份、父文件夹归属与同级重名后，用雪花 ID 落库 */
    @PostMapping("/folders")
    @Operation(summary = "创建文件夹", description = "创建新文件夹")
    public Result<String> createFolder(@Validated @RequestBody FileFolderDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.createFolder(dto, userId);
        return Result.success("文件夹创建成功");
    }

    /** 重命名文件夹：校验存在性与归属后更新名称 */
    @PutMapping("/folders/{id}")
    @Operation(summary = "更新文件夹", description = "重命名文件夹")
    public Result<String> updateFolder(@PathVariable Long id, @NotNull(message = "文件夹名称不能为空") @RequestParam String folderName) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.updateFolder(id, folderName, userId);
        return Result.success("文件夹重命名成功");
    }

    /** 删除文件夹：BFS 递归收集子树，删 OSS 文件（引用计数）+ 记录 + 释放配额 */
    @DeleteMapping("/folders/{id}")
    @Operation(summary = "删除文件夹", description = "删除文件夹，有子文件夹时不允许删除")
    public Result<String> deleteFolder(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.deleteFolder(id, userId);
        return Result.success("文件夹删除成功");
    }

    /** 移动文件夹：归属校验 + 防环（不可移入自身子树）+ 目标同名检查后更新 parentId */
    @PostMapping("/folders/move")
    @Operation(summary = "移动文件夹", description = "将文件夹移动到指定父文件夹")
    public Result<String> moveFolder(@RequestParam Long id,
                                     @RequestParam Long targetParentId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.moveFolder(id, targetParentId, userId);
        return Result.success("文件夹移动成功");
    }

    /** 批量移动：将多个文件夹和文件一次性移动到目标文件夹（同一事务） */
    @PostMapping("/folders/batch-move")
    @Operation(summary = "批量移动文件夹和文件", description = "将多个文件夹和文件批量移动到目标文件夹")
    public Result<String> batchMoveToFolder(@RequestBody FileFolderContentVO vo) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.batchMoveToFolder(vo, userId);
        return Result.success("批量移动成功");
    }

    // ==================== 文件夹导出 ====================

    /** 发起异步导出：Redis 锁限流（每用户 1 任务）+ 创建任务，立即返回 taskId */
    @PostMapping("/folders/{id}/export")
    @Operation(summary = "导出文件夹", description = "发起异步打包任务，将文件夹递归压缩为 zip 并上传至 OSS 临时存储")
    public Result<Map<String, String>> exportFolder(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        String taskId = fileFolderService.exportFolder(id, userId);
        return Result.success(Map.of("taskId", taskId));
    }

    /** SSE 推送导出进度：服务端后台线程每 500ms 轮询 Redis 任务状态后推事件 */
    @GetMapping(value = "/folders/export/{taskId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "导出进度推送", description = "SSE 端点，实时推送文件夹打包进度")
    public SseEmitter getExportProgress(@PathVariable String taskId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        // 先校验任务存在且属于当前用户
        fileFolderService.getExportProgress(taskId, userId);

        SseEmitter emitter = new SseEmitter(0L); // 无超时

        // 后台轮询线程
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-export-" + taskId);
            t.setDaemon(true);
            return t;
        }).execute(() -> {
            try {
                while (true) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    FolderExportTask task;
                    try {
                        task = fileFolderService.getExportProgress(taskId, userId);
                    } catch (Exception e) {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(Map.of("message", e.getMessage())));
                        emitter.complete();
                        break;
                    }

                    if (task == null) {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(Map.of("message", "任务不存在")));
                        emitter.complete();
                        break;
                    }

                    switch (task.getStatus()) {
                        case FolderExportTask.STATUS_PENDING:
                        case FolderExportTask.STATUS_PROCESSING:
                            emitter.send(SseEmitter.event()
                                    .name("progress")
                                    .data(Map.of(
                                            "processedFiles", task.getProcessedFiles(),
                                            "totalFiles", task.getTotalFiles(),
                                            "processedBytes", task.getProcessedBytes(),
                                            "totalBytes", task.getTotalBytes(),
                                            "percent", task.getPercent()
                                    )));
                            break;

                        case FolderExportTask.STATUS_COMPLETED:
                            emitter.send(SseEmitter.event()
                                    .name("complete")
                                    .data(Map.of(
                                            "taskId", task.getTaskId(),
                                            "fileName", task.getFolderName() + ".zip"
                                    )));
                            emitter.complete();
                            return;

                        case FolderExportTask.STATUS_FAILED:
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(Map.of("message",
                                            task.getErrorMessage() != null
                                                    ? task.getErrorMessage()
                                                    : "导出失败")));
                            emitter.complete();
                            return;

                        case FolderExportTask.STATUS_CANCELLED:
                            emitter.send(SseEmitter.event()
                                    .name("cancelled")
                                    .data(Map.of()));
                            emitter.complete();
                            return;

                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                log.warn("SSE 推送中断, taskId={}", taskId, e);
                emitter.completeWithError(e);
            }
        });

        emitter.onCompletion(() -> log.debug("SSE 完成, taskId={}", taskId));
        emitter.onTimeout(() -> {
            log.debug("SSE 超时, taskId={}", taskId);
            emitter.complete();
        });
        emitter.onError(ex -> log.warn("SSE 异常, taskId={}", taskId, ex));

        return emitter;
    }

    /** 取消导出：将任务状态改为 CANCELLED，异步线程检测后中止并清理 OSS 分片 */
    @DeleteMapping("/folders/export/{taskId}")
    @Operation(summary = "取消导出", description = "取消正在进行的文件夹导出任务")
    public Result<String> cancelExport(@PathVariable String taskId) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileFolderService.cancelExport(taskId, userId);
        return Result.success("已取消");
    }

    /** 下载导出 zip：校验任务完成后 302 重定向到 OSS 5 分钟预签名 URL */
    @GetMapping("/folders/export/{taskId}/download")
    @Operation(summary = "下载导出文件", description = "302 重定向到 OSS 预签名 URL，浏览器原生下载")
    public void downloadExportZip(@PathVariable String taskId, HttpServletResponse response) throws IOException {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        String presignedUrl = fileFolderService.getExportDownloadUrl(taskId, userId);
        response.sendRedirect(presignedUrl);
    }

    // 文件资源
    /** 删除单个文件：OSS 引用计数保护秒传共享对象，删记录并释放配额（事务） */
    @DeleteMapping("/file/{id}")
    @Operation(summary = "删除文件", description = "删除文件资源")
    public Result<String> deleteFile(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.deleteFile(id, userId);
        return Result.success("文件删除成功");
    }

    /** 按 ID 查询文件详情：id + userId 联合校验，防水平越权 */
    @GetMapping("/file/{id}")
    @Operation(summary = "获取文件详情", description = "根据文件ID获取文件信息")
    public Result<FileResourceVO> getFileById(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileResourceVO vo = fileResourceService.getFileByIdAndUserId(id,userId);
        return Result.success(vo);
    }

    /** 移动文件到目标文件夹：多重校验 + 同名冲突自动改名 name(n+1).ext（事务） */
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
    /** 更新文件解析状态：0-未解析 1-解析中 2-向量化完成 3-解析失败 */
    @PutMapping("/file/{id}/parse-status")
    @Operation(summary = "更新解析状态", description = "更新文件的解析状态，0-未解析，1-解析中，2-向量化完成，3-解析失败")
    public Result<String> updateFileParseStatus(@PathVariable Long id, @RequestParam Integer parseStatus) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.updateParseStatus(id, parseStatus);
        return Result.success("解析状态更新成功");
    }

    /** 初始化分片上传：三分支（Redis 续传 / fileHash 秒传 / 全新 OSS 初始化）并落元数据 */
    @PostMapping("/file/init")
    public Result<Map<String, Object>> initUpload(@RequestBody InitFileUploadDTO dto) {
        log.info("【云盘】初始化OSS分片上传, dto={}", dto);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        Map<String, Object> result = fileResourceService.initMultipartUpload(dto, userId);
        return Result.success(result,"初始化上传任务成功");
    }

    /** 获取分片上传预签名 URL（15 分钟），前端据此直传 OSS 不经后端 */
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

    /** 通知分片上传完成：将 eTag 回写到 Redis 任务缓存（断点续传依据） */
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

    /** 合并分片：OSS 合并 + 清理任务缓存 + 配额结算 + uploadStatus 置 1（事务） */
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

    /** 取消上传：中止 OSS 分片任务 + 清理 Redis 缓存 + 标记上传失败 */
    @PostMapping("/file/cancel")
    public Result<String> cancelUpload(@RequestParam String fileHash) {
        log.info("【云盘】取消文件上传, filehash={}", fileHash);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        fileResourceService.cancelUpload(fileHash, userId);
        return Result.success("文件上传取消成功");
    }

    /** 下载文件：校验归属后返回 OSS 公共读直链，由浏览器直连 OSS 下载，后端不再代理文件流 */
    @GetMapping("/download/{id}")
    @Operation(summary = "下载文件", description = "返回 OSS 公共读直链，前端直连 OSS 下载")
    public Result<String> downloadFile(@PathVariable String id) {
        Long downloadFileId = Long.parseLong(id);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        String accessUrl = fileResourceService.getFileAccessUrl(downloadFileId, userId);
        return Result.success(accessUrl);
    }

    /** 在线预览文件：同样返回 OSS 公共读直链，前端按文件类型内联渲染（img/iframe/docx-preview 等） */
    @GetMapping("/preview/{id}")
    @Operation(summary = "预览文件", description = "返回 OSS 公共读直链，前端直连 OSS 在线预览")
    public Result<String> previewFile(@PathVariable String id) {
        Long previewFileId = Long.parseLong(id);
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        String accessUrl = fileResourceService.getFileAccessUrl(previewFileId, userId);
        return Result.success(accessUrl);
    }

}
