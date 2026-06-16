package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;

/**
 * 学生画像候选信息提取器接口，仅从当前用户消息中提取
 */
public interface ProfileUpdateCandidateExtractor {

    /** 从当前用户消息中提取学生画像候选更新 **/
    UpdateStudentProfileRequest extract(String currentUserMessage);
}
