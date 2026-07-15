package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.model.vo.FeaturedResourceVO;
import com.smartorder.edu.agent.service.ResourceSquareRecommendService;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源广场 Agent 相关接口。
 * <p>
 * 与 smartorder-student 的 {@code ResourceSquareController} 共用 {@code /resource-square}
 * 前缀，本控制器仅注册 {@code GET /featured} 字面路径，Spring 按字面量优先于
 * {@code /{id}} 路径变量匹配，无冲突。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource-square")
@Tag(name = "资源广场-Agent", description = "资源广场的 Agent 精选推荐接口")
public class ResourceSquareAgentController {

    private final ResourceSquareRecommendService recommendService;

    @GetMapping("/featured")
    @Operation(summary = "Agent 精选推荐", description = "基于学生画像从评分 Top20 候选中挑选 1 个精选资源")
    public Result<FeaturedResourceVO> getFeatured() {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        FeaturedResourceVO vo = recommendService.recommendFeatured(userId);
        return Result.success(vo);
    }
}
