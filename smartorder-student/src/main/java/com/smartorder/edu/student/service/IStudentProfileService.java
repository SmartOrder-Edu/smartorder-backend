package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileDimensionUpdateDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileUpdateDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;

import java.util.List;

/**
 * 学生画像服务接口
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
public interface IStudentProfileService {

    /**
     * 根据用户ID获取画像
     */
    StudentProfile getByUserId(Long userId);

    /**
     * 创建或更新画像
     */
    void saveOrUpdate(StudentProfile profile);

    /**
     * 全量更新画像（深度合并前端提交的各维度数据）
     *
     * @param userId  用户ID
     * @param profile 前端提交的画像维度
     * @return 更新后的完整画像
     */
    StudentProfile updateProfile(Long userId, StudentProfileUpdateDTO profile);

    /**
     * 引导页初始化画像 — 将引导页 DTO 映射到 learningBackground 和 learningGoals 维度
     *
     * @return 更新后的完整画像
     */
    StudentProfile initProfile(Long userId, ProfileInitDTO dto);

    /**
     * 增量更新画像的指定维度（深度合并）
     *
     * @param userId       用户ID
     * @param update 维度更新数据
     * @return 更新后的完整画像
     */
    StudentProfile updateProfileDimension(Long userId, StudentProfileDimensionUpdateDTO update);

    /**
     * 批量增量更新画像的多个维度（一次 DB 读写，版本号只递增一次）
     *
     * @param userId            用户ID
     * @param dimensionUpdates  维度更新列表，每项包含 dimensionKey、delta、reason
     */
    void updateProfileDimensions(Long userId, List<StudentProfileDimensionUpdateDTO> dimensionUpdates);

    /**
     * 删除指定用户的画像
     *
     * @param userId 用户ID
     */
    void deleteByUserId(Long userId);
}
