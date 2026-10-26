package com.smartorder.edu.agent.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.agent.config.AgentConfiguration;
import com.smartorder.edu.agent.model.vo.FeaturedResourceVO;
import com.smartorder.edu.student.domain.dto.ResourceSquareListQueryDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.domain.vo.ResourceSquareVO;
import com.smartorder.edu.student.service.IResourceSquareService;
import com.smartorder.edu.student.service.IStudentProfileService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 资源广场精选推荐服务。
 * <p>
 * 从评分 Top20 候选中，结合学生画像的 profileSummary，调用 planningChatModel
 * （JSON 输出、无 thinking）挑选 1 个最契合的资源。任何环节失败（超时、返回
 * 非候选 ID、画像未初始化）都诚实回退到综合评分最高候选，并通过 reason="hot"
 * 告知前端不可挂 "Agent" 徽标，遵守产品反幻觉原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceSquareRecommendService {

    private final IResourceSquareService resourceSquareService;
    private final IStudentProfileService studentProfileService;

    /** 字段名匹配 AgentConfiguration.planningChatModel Bean（JSON 输出、无 thinking，解析可靠） */
    private final ChatModel planningChatModel;

    private static final int CANDIDATE_SIZE = 20;
    private static final long LLM_TIMEOUT_SECONDS = 8;

    public FeaturedResourceVO recommendFeatured(Long userId) {
        // 1. 取评分 Top20 候选
        ResourceSquareListQueryDTO query = new ResourceSquareListQueryDTO();
        query.setSortBy("rating");
        query.setPageNum(1);
        query.setPageSize(CANDIDATE_SIZE);
        Page<ResourceSquareVO> page = resourceSquareService.list(query, userId);
        List<ResourceSquareVO> candidates = page == null ? null : page.getRecords();
        if (candidates == null || candidates.isEmpty()) {
            // 空广场：前端隐藏精选横幅
            return null;
        }
        if (candidates.size() == 1) {
            return build(candidates.get(0), "hot");
        }

        // 2. 画像：getByUserId 不会返回 null，但内容可能为空（自动初始化）
        StudentProfile profile = null;
        try {
            profile = studentProfileService.getByUserId(userId);
        } catch (Exception e) {
            log.warn("精选推荐：加载画像失败，回退热门。userId={}, err={}", userId, e.getMessage());
        }
        if (profile == null || isBlank(profile.getProfileSummary())) {
            // 画像未个性化，无法做 Agent 推荐，诚实回退
            return build(topByScore(candidates), "hot");
        }

        // 3. 调 LLM 选 1 个
        try {
            Long pickedId = callLlmPick(profile.getProfileSummary(), candidates);
            if (pickedId != null) {
                Set<Long> candidateIds = candidates.stream()
                        .map(ResourceSquareVO::getId)
                        .collect(Collectors.toSet());
                if (candidateIds.contains(pickedId)) {
                    ResourceSquareVO featured = resourceSquareService.getDetail(pickedId, userId);
                    if (featured != null) {
                        return build(featured, "agent");
                    }
                }
                log.warn("精选推荐：LLM 返回的 resourceId={} 不在候选集，回退热门", pickedId);
            }
        } catch (Exception e) {
            log.warn("精选推荐：LLM 调用异常，回退热门。err={}", e.getMessage());
        }
        return build(topByScore(candidates), "hot");
    }

    /**
     * 同步调用 LLM，带 8s 超时。
     * <p>
     * 注意：超时后底层 chat() 仍可能在 ForkJoinPool 线程中跑到模型自身 120s 超时；
     * 精选端点流量低，可接受。如后续高流量可换成专用 Executor。
     */
    private Long callLlmPick(String profileSummary, List<ResourceSquareVO> candidates) {
        String prompt = buildPrompt(profileSummary, candidates);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> planningChatModel.chat(prompt));
        String response;
        try {
            response = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("精选推荐：LLM 超时或中断: {}", e.getMessage());
            return null;
        }
        return parseResourceId(response);
    }

    private String buildPrompt(String profileSummary, List<ResourceSquareVO> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是学习资源推荐助手。请根据学生画像，从候选资源中选出最适合该学生的一个。\n\n");
        sb.append("## 学生画像摘要\n").append(profileSummary).append("\n\n");
        sb.append("## 候选资源列表\n");
        for (ResourceSquareVO r : candidates) {
            sb.append("- id=").append(r.getId())
                    .append(" | 标题=").append(nullToEmpty(r.getTitle()))
                    .append(" | 分类=").append(nullToEmpty(r.getCategory()))
                    .append(" | 标签=").append(r.getTags() == null ? "" : String.join(",", r.getTags()))
                    .append(" | 评分=").append(r.getRatingAvg() == null ? "0" : r.getRatingAvg())
                    .append(" | 转存量=").append(r.getDownloadCount() == null ? 0 : r.getDownloadCount())
                    .append("\n");
        }
        sb.append("\n## 要求\n");
        sb.append("只返回一个 JSON 对象，格式为 {\"resourceId\": <候选id>}，");
        sb.append("不要输出任何其他文字或解释。resourceId 必须来自上面的候选列表。");
        return sb.toString();
    }

    /** 解析 LLM 返回，支持纯 JSON 与混入文本两种情况。 */
    private Long parseResourceId(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(response);
            if (obj != null && obj.containsKey("resourceId")) {
                return obj.getLong("resourceId");
            }
        } catch (Exception ignored) {
            // 非纯 JSON，回退到数字提取
        }
        // 兜底：提取第一段数字
        String digits = response.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 综合评分：ratingAvg + log10(downloadCount+10)，用于诚实热门回退。 */
    private ResourceSquareVO topByScore(List<ResourceSquareVO> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(this::compositeScore))
                .orElse(candidates.get(0));
    }

    private double compositeScore(ResourceSquareVO r) {
        double rating = r.getRatingAvg() == null ? 0 : r.getRatingAvg().doubleValue();
        int dl = r.getDownloadCount() == null ? 0 : r.getDownloadCount();
        return rating + Math.log10(dl + 10);
    }

    private FeaturedResourceVO build(ResourceSquareVO resource, String reason) {
        FeaturedResourceVO vo = new FeaturedResourceVO();
        vo.setResource(resource);
        vo.setReason(reason);
        return vo;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
