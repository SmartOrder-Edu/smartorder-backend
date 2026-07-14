package com.smartorder.edu.student.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.student.domain.dto.ResourceSquareListQueryDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquarePublishDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquareRateDTO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.domain.vo.ResourceSquareVO;
import com.smartorder.edu.student.service.IFileResourceService.FileDownloadResult;

/**
 * 资源广场服务接口
 */
public interface IResourceSquareService {

    /**
     * 发布文件到资源广场
     */
    ResourceSquareVO publish(ResourceSquarePublishDTO dto, Long userId);

    /**
     * 下架资源（仅发布者本人可操作）
     */
    void unpublish(Long id, Long userId);

    /**
     * 分页查询资源广场列表
     */
    Page<ResourceSquareVO> list(ResourceSquareListQueryDTO query, Long userId);

    /**
     * 获取资源详情（含当前用户的评分和转存状态）
     */
    ResourceSquareVO getDetail(Long id, Long userId);

    /**
     * 转存资源到当前用户的云盘
     */
    FileResourceVO download(Long id, Long userId);

    /**
     * 评分（一人一票，重复评分覆盖旧值）
     */
    void rate(Long id, ResourceSquareRateDTO dto, Long userId);

    /**
     * 预览资源文件（无需登录校验所有权）
     */
    FileDownloadResult preview(Long id);
}
