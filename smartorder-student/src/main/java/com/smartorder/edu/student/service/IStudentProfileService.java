package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;

import java.util.List;
import java.util.Map;

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

    /**
     * 增量更新画像的指定维度（深度合并）
     *
     * @param userId       用户ID
     * @param dimensionKey 维度名称，如 "knowledge_profile", "weakness_profile"
     * @param delta        增量数据，将与现有数据深度合并
     * @param reason       更新原因
     */
    void updateProfileDimension(Long userId, String dimensionKey, Map<String, Object> delta, String reason);

    /**
     * 批量增量更新画像的多个维度（一次 DB 读写，版本号只递增一次）
     *
     * @param userId            用户ID
     * @param dimensionUpdates  维度更新列表，每项包含 dimensionKey、delta、reason
     */
    void updateProfileDimensions(Long userId, List<DimensionUpdateDTO> dimensionUpdates);

    /**
     * 维度更新数据项
     */
    record DimensionUpdateDTO(String dimensionKey, Map<String, Object> delta, String reason) {}
}
