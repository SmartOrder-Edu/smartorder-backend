package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;

/**
 * 学生画像服务接口
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
public interface IStudentProfileService {

    /**
     * 根据用户ID获取全局画像（subject_id IS NULL）
     */
    StudentProfile getByUserId(Long userId);

    /**
     * 根据用户ID和科目ID获取画像
     */
    StudentProfile getByUserIdAndSubject(Long userId, Long subjectId);

    /**
     * 创建或更新画像
     */
    void saveOrUpdate(StudentProfile profile);

    /**
     * 引导页初始化画像 — 更新已有的空画像记录
     */
    void initProfile(Long userId, ProfileInitDTO dto);
}
