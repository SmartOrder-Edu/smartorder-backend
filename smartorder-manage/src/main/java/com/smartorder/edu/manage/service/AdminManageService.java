package com.smartorder.edu.manage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.manage.domain.vo.AdminDashboardChartsVO;
import com.smartorder.edu.manage.domain.vo.AdminDashboardStatsVO;
import com.smartorder.edu.manage.domain.vo.AgentResourceLogVO;
import com.smartorder.edu.manage.domain.vo.AgentToolExecutionVO;
import com.smartorder.edu.manage.domain.vo.KnowledgeFileJobVO;

/**
 * 后台综合管理服务接口
 */
public interface AdminManageService {

    /**
     * 获取管理大盘各项指标汇总数据
     */
    AdminDashboardStatsVO getDashboardStats();

    /**
     * 获取管理大盘图表数据
     */
    AdminDashboardChartsVO getDashboardCharts();

    /**
     * 分页查询工具审计日志
     */
    Page<AgentToolExecutionVO> queryToolExecutionPage(int page, int size, String toolName, String status);

    /**
     * 分页查询分阶段资源生成日志
     */
    Page<AgentResourceLogVO> queryResourceLogPage(int page, int size, String resourceType, String status);

    /**
     * 分页查询知识库异步解析文件队列
     */
    Page<KnowledgeFileJobVO> queryRAGJobPage(int page, int size, String status);

    /**
     * 重新触发异步解析文件任务
     */
    boolean retryRAGJob(Long jobId);
}
