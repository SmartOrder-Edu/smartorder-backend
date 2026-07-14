package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import lombok.Builder;

/**
 * 后台大盘统计卡片数据 VO
 */
@Data
@Builder
public class AdminDashboardStatsVO {
    private Long userCount;            // 用户总数
    private Long agentExecutionCount; // 今日智能体工具调用次数
    private Long pendingRAGJobCount;  // 待处理或处理中的知识库向量化任务数
    private Long pendingFeedbackCount;// 待复核用户反馈数
}
