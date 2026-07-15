package com.smartorder.edu.agent.model.vo;

import com.smartorder.edu.student.domain.vo.ResourceSquareVO;
import lombok.Data;

/**
 * 资源广场精选推荐结果。
 * <p>
 * reason 表示精选来源：
 * <ul>
 *   <li>"agent" - 由 LLM 基于学生画像从候选集中挑选</li>
 *   <li>"hot" - LLM 不可用 / 画像未初始化 / 返回非法 ID 时的热门回退（综合评分最高）</li>
 * </ul>
 * 前端依据 reason 诚实展示徽标，不把启发式打分伪装成 Agent 推荐。
 */
@Data
public class FeaturedResourceVO {

    /** 精选资源（复用广场 VO，含 hasDownloaded / userRating 等当前用户态字段） */
    private ResourceSquareVO resource;

    /** 精选来源：agent | hot */
    private String reason;
}
