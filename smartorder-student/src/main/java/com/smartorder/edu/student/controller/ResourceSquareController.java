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
import com.smartorder.edu.student.service.IResourceSquareService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
}
