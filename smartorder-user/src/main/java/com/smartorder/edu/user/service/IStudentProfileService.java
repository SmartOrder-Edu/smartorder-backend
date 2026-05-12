package com.smartorder.edu.user.service;

import com.smartorder.edu.user.domain.entity.StudentProfile;

/**
 * 学生画像服务接口
 */
public interface IStudentProfileService {

    /**
     * 根据用户ID获取学生画像
     * @param userId 用户ID
     * @return 学生画像，不存在返回null
     */
    StudentProfile getByUserId(Long userId);

    /**
     * 创建或更新学生画像
     */
    void saveOrUpdate(StudentProfile profile);
}
