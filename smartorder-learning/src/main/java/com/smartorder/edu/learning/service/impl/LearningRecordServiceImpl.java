package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.LearningRecord;
import com.smartorder.edu.learning.mapper.LearningRecordMapper;
import com.smartorder.edu.learning.service.ILearningRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl implements ILearningRecordService {

    private final LearningRecordMapper learningRecordMapper;

    @Override
    public List<LearningRecord> getByUserIdAndDate(Long userId, LocalDate date) {
        QueryWrapper<LearningRecord> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("record_date", date).eq("is_deleted", 0).orderByDesc("create_time");
        return learningRecordMapper.selectList(qw);
    }

    @Override
    public List<LearningRecord> getByUserIdAndSpaceId(Long userId, Long spaceId) {
        QueryWrapper<LearningRecord> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("space_id", spaceId).eq("is_deleted", 0).orderByDesc("record_date");
        return learningRecordMapper.selectList(qw);
    }

    @Override
    public List<LearningRecord> getRecentByUserId(Long userId, int days) {
        QueryWrapper<LearningRecord> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0)
                .ge("record_date", LocalDate.now().minusDays(days)).orderByDesc("record_date");
        return learningRecordMapper.selectList(qw);
    }

    @Override
    public List<LearningRecord> getBySpaceIdAndDateRange(Long spaceId, Long userId, LocalDate startDate, LocalDate endDate) {
        QueryWrapper<LearningRecord> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .ge("record_date", startDate).le("record_date", endDate).orderByDesc("record_date");
        return learningRecordMapper.selectList(qw);
    }

    @Override
    public void saveOrUpdate(LearningRecord record) {
        if (record.getIsDeleted() == null) record.setIsDeleted(0);
        if (record.getId() == null) {
            learningRecordMapper.insert(record);
        } else {
            learningRecordMapper.updateById(record);
        }
    }
}
