package com.smartorder.edu.student.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.ResourceSquareListQueryDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquarePublishDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquareRateDTO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.domain.vo.ResourceSquareVO;
import com.smartorder.edu.student.service.IFileResourceService.FileDownloadResult;
import com.smartorder.edu.student.service.IResourceSquareService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource-square")
@Tag(name = "资源广场", description = "资源广场的发布、浏览、下载、评分接口")
public class ResourceSquareController {

    private final IResourceSquareService resourceSquareService;

    @PostMapping("/publish")
    @Operation(summary = "发布到资源广场")
    public Result<ResourceSquareVO> publish(@Validated @RequestBody ResourceSquarePublishDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        ResourceSquareVO vo = resourceSquareService.publish(dto, userId);
        return Result.success(vo, "发布成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "下架资源")
    public Result<String> unpublish(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        resourceSquareService.unpublish(id, userId);
        return Result.success("下架成功");
    }

    @GetMapping("/list")
    @Operation(summary = "资源广场列表")
    public Result<Page<ResourceSquareVO>> list(@Validated ResourceSquareListQueryDTO query) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        Page<ResourceSquareVO> page = resourceSquareService.list(query, userId);
        return Result.success(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "资源广场详情")
    public Result<ResourceSquareVO> getDetail(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        ResourceSquareVO vo = resourceSquareService.getDetail(id, userId);
        return Result.success(vo);
    }

    @PostMapping("/{id}/download")
    @Operation(summary = "转存到我的云盘")
    public Result<FileResourceVO> download(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FileResourceVO vo = resourceSquareService.download(id, userId);
        return Result.success(vo, "转存成功");
    }

    @PostMapping("/{id}/rate")
    @Operation(summary = "评分")
    public Result<String> rate(@PathVariable Long id, @Validated @RequestBody ResourceSquareRateDTO dto) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        resourceSquareService.rate(id, dto, userId);
        return Result.success("评分成功");
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "预览资源文件")
    public void preview(@PathVariable Long id, HttpServletResponse response) throws IOException {
        FileDownloadResult result = resourceSquareService.preview(id);
        InputStream inputStream = null;
        try {
            inputStream = result.inputStream();
            response.reset();

            String fileType = result.fileType() != null ? result.fileType().toLowerCase() : "";
            String contentType = getContentType(fileType);
            response.setContentType(contentType);
            response.setCharacterEncoding("UTF-8");

            String fileName = result.fileName() != null ? result.fileName() : "未命名文件";
            if (!fileType.isEmpty()) {
                String extension = "." + fileType;
                if (!fileName.endsWith(extension)) {
                    fileName += extension;
                }
            }
            String encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" + encodedName);
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
            log.error("资源广场文件预览失败, id={}", id, e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"文件预览失败\"}");
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private String getContentType(String fileType) {
        if (fileType == null) return "application/octet-stream";
        return switch (fileType.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "txt", "md", "log" -> "text/plain;charset=UTF-8";
            case "html" -> "text/html;charset=UTF-8";
            case "css" -> "text/css;charset=UTF-8";
            case "js" -> "application/javascript;charset=UTF-8";
            case "json" -> "application/json;charset=UTF-8";
            case "xml" -> "application/xml;charset=UTF-8";
            case "java", "py", "ts", "yaml", "yml" -> "text/plain;charset=UTF-8";
            case "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }
}
