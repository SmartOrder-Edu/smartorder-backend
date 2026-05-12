package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.dto.LearningSpaceDTO;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;

import java.util.List;

/**
 * 学习空间服务接口
 * @author 谢光湘
 * @since 2026/5/12
 */
public interface ILearningSpaceService {

    /**
     * 创建学习空间
     * @param userId 用户ID
     * @param dto 学习空间数据传输对象
     * @return 创建后的学习空间视图对象
     */
    LearningSpaceVO createSpace(Long userId, LearningSpaceDTO dto);

    /**
     * 获取单个学习空间详情
     * @param userId 用户ID
     * @param spaceId 空间ID
     * @return 学习空间视图对象
     */
    LearningSpaceVO getSpaceById(Long userId, Long spaceId);

    /**
     * 获取用户的学习空间列表
     * @param userId 用户ID
     * @param status 状态过滤（可选，为null时返回全部）
     * @return 学习空间视图对象列表
     */
    List<LearningSpaceVO> listSpaces(Long userId, String status);

    /**
     * 更新学习空间
     * @param userId 用户ID
     * @param spaceId 空间ID
     * @param dto 学习空间数据传输对象
     * @return 更新后的学习空间视图对象
     */
    LearningSpaceVO updateSpace(Long userId, Long spaceId, LearningSpaceDTO dto);

    /**
     * 删除学习空间（逻辑删除）
     * @param userId 用户ID
     * @param spaceId 空间ID
     */
    void deleteSpace(Long userId, Long spaceId);
}
