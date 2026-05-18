package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import com.smartorder.edu.student.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author 谢光湘
 * @since 2026-05-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentProfileServiceImpl implements IStudentProfileService {

    private final StudentProfileMapper studentProfileMapper;

    @Override
    public StudentProfile getByUserId(Long userId) {
        QueryWrapper<StudentProfile> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
          .isNull("subject_id")
          .last("LIMIT 1");
        return studentProfileMapper.selectOne(qw);
    }

    @Override
    public StudentProfile getByUserIdAndSubject(Long userId, Long subjectId) {
        QueryWrapper<StudentProfile> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (subjectId != null) {
            qw.eq("subject_id", subjectId);
        } else {
            qw.isNull("subject_id");
        }
        qw.last("LIMIT 1");
        return studentProfileMapper.selectOne(qw);
    }

    @Override
    public void saveOrUpdate(StudentProfile profile) {
        if (profile.getId() == null) {
            studentProfileMapper.insert(profile);
            log.info("创建学生画像: userId={}, id={}", profile.getUserId(), profile.getId());
        } else {
            studentProfileMapper.updateById(profile);
            log.info("更新学生画像: userId={}, id={}", profile.getUserId(), profile.getId());
        }
    }
}
