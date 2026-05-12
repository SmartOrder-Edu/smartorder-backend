package com.smartorder.edu.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.user.domain.entity.StudentProfile;
import com.smartorder.edu.user.mapper.StudentProfileMapper;
import com.smartorder.edu.user.service.IStudentProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 学生画像服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentProfileServiceImpl implements IStudentProfileService {

    private final StudentProfileMapper studentProfileMapper;

    @Override
    public StudentProfile getByUserId(Long userId) {
        QueryWrapper<StudentProfile> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("is_deleted", 0).last("limit 1");
        return studentProfileMapper.selectOne(qw);
    }

    @Override
    public void saveOrUpdate(StudentProfile profile) {
        if (profile.getId() == null) {
            studentProfileMapper.insert(profile);
        } else {
            studentProfileMapper.updateById(profile);
        }
    }
}
