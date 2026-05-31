package com.smartorder.edu.common.controller;


import com.smartorder.edu.common.model.vo.KnowledgeBaseDetailStatsVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseListRespVO;
import com.smartorder.edu.common.model.vo.KnowledgeBaseStatsVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.service.IKnowledgeBaseService;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.model.dto.KnowledgeBaseReqDTO;

import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/knowledge/base")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final AliOSSUtils aliOSSUtils;

    private final IKnowledgeBaseService  knowledgeBaseService;

    /**
     * 步骤 1：上传知识库封面
     */
    @PostMapping("/upload/cover")
    public String uploadCover(@RequestParam("file") MultipartFile file) {
        try {
            // 调用你提供的工具类，上传到指定的目录，例如 kb-covers/
            return aliOSSUtils.upload(file, "kb-covers/");
        } catch (IOException e) {
            log.error("封面上传失败", e);
            throw new RuntimeException("图片上传失败，请稍后重试");
        }
    }

    /**
     * 步骤 2：提交表单新建知识库
     */
    @PostMapping("/create")
    public Long createKnowledgeBase(@Validated @RequestBody KnowledgeBaseReqDTO reqDTO) {

        Claims claims = ThreadLocalUtils.get();

        Long currentUserId =  claims.get("userId", Long.class);

        return knowledgeBaseService.createKnowledgeBase(reqDTO, currentUserId);
    }

    /**
     * 获取当前用户的知识库列表
     */
    @GetMapping("/list")
    public List<KnowledgeBaseListRespVO> getKnowledgeBaseList() {
        // 获取当前登录用户 ID
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        // 调用 Service 获取列表
        return knowledgeBaseService.getKnowledgeBaseList(currentUserId);
    }

    /**
     * 提交表单编辑知识库 (复用 uploadCover 进行图片上传)
     */
    @PostMapping("/update") // 也可以用 @PutMapping("/update")
    public Boolean updateKnowledgeBase(@Validated @RequestBody KnowledgeBaseReqDTO reqDTO) {

        // 获取当前登录用户 ID
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        // 调用 Service 执行更新
        return knowledgeBaseService.updateKnowledgeBase(reqDTO, currentUserId);
    }

    /**
     * 删除知识库（级联删除所有相关数据）
     */
    @DeleteMapping("delete/{id}")
    public Result deleteKnowledgeBase(@PathVariable Long id) {
        // 获取当前登录用户 ID
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        // 调用 Service 执行删除
        knowledgeBaseService.deleteKnowledgeBase(id, currentUserId);
        return Result.success("删除知识库成功");
    }

    /**
     * 获取当前用户的知识库统计信息
     */
    @GetMapping("/stats")
    public KnowledgeBaseStatsVO getKnowledgeBaseStats() {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);
        return knowledgeBaseService.getKnowledgeBaseStats(currentUserId);
    }

    /**
     * 获取指定知识库的详细统计信息
     */
    @GetMapping("/{id}/stats")
    public KnowledgeBaseDetailStatsVO getKnowledgeBaseDetailStats(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);
        return knowledgeBaseService.getKnowledgeBaseDetailStats(id, currentUserId);
    }

}
