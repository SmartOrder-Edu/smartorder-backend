package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.LearningRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习记录服务接口
 */
public interface ILearningRecordService {

    /**
     * 根据用户ID和日期查询学习记录
     */
    List<LearningRecord> getByUserIdAndDate(Long userId, LocalDate date);

    /**
     * 根据用户ID和学习空间ID查询学习记录
     */
    List<LearningRecord> getByUserIdAndSpaceId(Long userId, Long spaceId);

    /**
     * 查询用户最近N天的学习记录
     */
    List<LearningRecord> getRecentByUserId(Long userId, int days);

    /**
     * 保存或更新学习记录
     */
    void saveOrUpdate(LearningRecord record);
}
