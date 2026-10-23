package com.smartorder.edu.manage.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 后台大盘图表分析数据 VO
 */
@Data
@Builder
public class AdminDashboardChartsVO {
    // 趋势图数据：日期列表，例如 ["07-02", "07-03", ...]
    private List<String> trendDates;
    
    // 趋势图数据：每天的成功调用次数
    private List<Long> successCounts;
    
    // 趋势图数据：每天的失败调用次数
    private List<Long> failCounts;

    // 饼图数据：知识库文件类型分布，例如 [{"name": "PDF", "value": 12}, {"name": "DOCX", "value": 8}]
    private List<Map<String, Object>> fileTypeDistribution;
    
    // 饼图数据：用户反馈类型构成
    private List<Map<String, Object>> feedbackTypeDistribution;
}
