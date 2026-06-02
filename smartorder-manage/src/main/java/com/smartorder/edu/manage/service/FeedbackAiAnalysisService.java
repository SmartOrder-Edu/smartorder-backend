package com.smartorder.edu.manage.service;

/**
 * 反馈AI分析服务接口
 */
public interface FeedbackAiAnalysisService {
    
    /**
     * 对用户反馈进行分析
     * @param content 用户反馈内容
     * @param type 反馈类型
     * @return JSON格式的分析结果（包含真实性判断、是否通过、初步意见）
     */
    String analyzeFeedback(String content, Integer type);
}
