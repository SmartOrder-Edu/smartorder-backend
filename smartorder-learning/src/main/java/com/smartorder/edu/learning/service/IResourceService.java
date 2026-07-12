package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Resource;

import java.util.List;

public interface IResourceService {

    List<Resource> getBySpaceId(Long spaceId);

    Resource getById(Long resourceId);

    /**
     * 批量插入"计划中"的资源（草案确认时使用）。
     * 统一设置：status='planned'、isDeleted=0；
     * 调用方需自行设置 isAiGenerated / source。
     *
     * @param resources 待插入资源列表（id 由 MyBatis 回填）
     * @return 已插入的资源（带 id）
     */
    List<Resource> batchInsertPlanned(List<Resource> resources);

    /**
     * 批量插入已生成资源（资料包确认后使用）。
     */
    List<Resource> batchInsertGenerated(List<Resource> resources);

    void updateStatus(Long resourceId, String status);

    void markGenerated(Long resourceId, String fileFormat);
}
