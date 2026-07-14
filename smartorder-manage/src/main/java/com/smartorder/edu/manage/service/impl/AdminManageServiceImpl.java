package com.smartorder.edu.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.mapper.KnowledgeBaseMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.model.entity.KnowledgeBase;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.manage.domain.po.AgentToolExecution;
import com.smartorder.edu.manage.domain.vo.AdminDashboardChartsVO;
import com.smartorder.edu.manage.domain.vo.AdminDashboardStatsVO;
import com.smartorder.edu.manage.domain.vo.AgentResourceLogVO;
import com.smartorder.edu.manage.domain.vo.AgentToolExecutionVO;
import com.smartorder.edu.manage.domain.vo.KnowledgeFileJobVO;
import com.smartorder.edu.manage.mapper.AgentToolExecutionMapper;
import com.smartorder.edu.manage.mapper.FeedbackMapper;
import com.smartorder.edu.manage.service.AdminManageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 后台综合管理服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminManageServiceImpl implements AdminManageService {

    private final UserMapper userMapper;
    private final AgentToolExecutionMapper toolExecutionMapper;
    private final KnowledgeFileJobMapper fileJobMapper;
    private final FeedbackMapper feedbackMapper;
    private final AgentResourceLogMapper resourceLogMapper;
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeBaseMapper baseMapper;

    @Override
    public AdminDashboardStatsVO getDashboardStats() {
        // 1. 用户总数
        Long userCount = userMapper.selectCount(null);

        // 2. 今日智能体工具调用数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Long agentExecutionCount = toolExecutionMapper.selectCount(
                new LambdaQueryWrapper<AgentToolExecution>()
                        .ge(AgentToolExecution::getCreateTime, todayStart)
        );

        // 3. 待处理/处理中的向量化解析文件数
        Long pendingRAGJobCount = fileJobMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeFileJob>()
                        .in(KnowledgeFileJob::getStatus, Arrays.asList("pending", "running"))
        );

        // 4. 未复核反馈数 (状态0=待AI初审，1=待复核)
        Long pendingFeedbackCount = feedbackMapper.selectCount(
                new QueryWrapper<com.smartorder.edu.manage.domain.po.Feedback>()
                        .in("status", Arrays.asList(0, 1))
                        .eq("is_deleted", 0)
        );

        return AdminDashboardStatsVO.builder()
                .userCount(userCount)
                .agentExecutionCount(agentExecutionCount)
                .pendingRAGJobCount(pendingRAGJobCount)
                .pendingFeedbackCount(pendingFeedbackCount)
                .build();
    }

    @Override
    public AdminDashboardChartsVO getDashboardCharts() {
        // 1. 生成近七日时间序列 (MM-dd)
        List<String> trendDates = new ArrayList<>();
        List<Long> successCounts = new ArrayList<>();
        List<Long> failCounts = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");

        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            trendDates.add(date.format(formatter));
            
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();

            // 统计当天成功数
            Long successNum = toolExecutionMapper.selectCount(
                    new LambdaQueryWrapper<AgentToolExecution>()
                            .ge(AgentToolExecution::getCreateTime, start)
                            .lt(AgentToolExecution::getCreateTime, end)
                            .eq(AgentToolExecution::getStatus, "SUCCESS")
            );
            successCounts.add(successNum);

            // 统计当天失败数
            Long failNum = toolExecutionMapper.selectCount(
                    new LambdaQueryWrapper<AgentToolExecution>()
                            .ge(AgentToolExecution::getCreateTime, start)
                            .lt(AgentToolExecution::getCreateTime, end)
                            .ne(AgentToolExecution::getStatus, "SUCCESS")
            );
            failCounts.add(failNum);
        }

        // 2. 统计知识库文件类型占比分布
        List<Map<String, Object>> fileTypeDistribution = new ArrayList<>();
        // 获取所有上传文件
        List<KnowledgeFile> files = fileMapper.selectList(null);
        if (files != null && !files.isEmpty()) {
            Map<String, Long> extGroup = files.stream()
                    .filter(f -> f.getFileExtension() != null)
                    .collect(Collectors.groupingBy(f -> f.getFileExtension().toUpperCase(), Collectors.counting()));
            extGroup.forEach((ext, count) -> {
                Map<String, Object> item = new HashMap<>();
                item.put("name", ext);
                item.put("value", count);
                fileTypeDistribution.add(item);
            });
        }

        // 3. 统计反馈类型构成分布 (1=体验评价，2=功能建议，3=Bug反馈，4=其他意见)
        List<Map<String, Object>> feedbackTypeDistribution = new ArrayList<>();
        List<com.smartorder.edu.manage.domain.po.Feedback> feedbacks = feedbackMapper.selectList(
                new QueryWrapper<com.smartorder.edu.manage.domain.po.Feedback>().eq("is_deleted", 0)
        );
        if (feedbacks != null && !feedbacks.isEmpty()) {
            Map<Integer, Long> typeGroup = feedbacks.stream()
                    .filter(fb -> fb.getType() != null)
                    .collect(Collectors.groupingBy(com.smartorder.edu.manage.domain.po.Feedback::getType, Collectors.counting()));
            
            typeGroup.forEach((type, count) -> {
                Map<String, Object> item = new HashMap<>();
                String typeName = switch (type) {
                    case 1 -> "体验评价";
                    case 2 -> "功能建议";
                    case 3 -> "Bug反馈";
                    default -> "其他意见";
                };
                item.put("name", typeName);
                item.put("value", count);
                feedbackTypeDistribution.add(item);
            });
        }

        return AdminDashboardChartsVO.builder()
                .trendDates(trendDates)
                .successCounts(successCounts)
                .failCounts(failCounts)
                .fileTypeDistribution(fileTypeDistribution)
                .feedbackTypeDistribution(feedbackTypeDistribution)
                .build();
    }

    @Override
    public Page<AgentToolExecutionVO> queryToolExecutionPage(int page, int size, String toolName, String status) {
        Page<AgentToolExecution> toolPage = new Page<>(page, size);
        LambdaQueryWrapper<AgentToolExecution> wrapper = new LambdaQueryWrapper<>();
        if (toolName != null && !toolName.trim().isEmpty()) {
            wrapper.like(AgentToolExecution::getToolName, toolName);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(AgentToolExecution::getStatus, status);
        }
        wrapper.orderByDesc(AgentToolExecution::getCreateTime);
        
        toolExecutionMapper.selectPage(toolPage, wrapper);

        Page<AgentToolExecutionVO> voPage = new Page<>(page, size, toolPage.getTotal());
        List<AgentToolExecution> records = toolPage.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        // 批量关联 User 名字，防止 N+1 查询
        Set<Long> userIds = records.stream().map(AgentToolExecution::getUserId).collect(Collectors.toSet());
        Map<Long, String> userMap = getUserNamesMap(userIds);

        List<AgentToolExecutionVO> voRecords = records.stream().map(po -> {
            AgentToolExecutionVO vo = new AgentToolExecutionVO();
            vo.setId(po.getId());
            vo.setTraceId(po.getTraceId());
            vo.setConversationId(po.getConversationId());
            vo.setUserId(po.getUserId());
            vo.setUserName(userMap.getOrDefault(po.getUserId(), "未知用户"));
            vo.setToolName(po.getToolName());
            vo.setArguments(po.getArguments());
            vo.setResult(po.getResult());
            vo.setStatus(po.getStatus());
            vo.setErrorMessage(po.getErrorMessage());
            vo.setDurationMs(po.getDurationMs());
            vo.setCreateTime(po.getCreateTime());
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voRecords);
        return voPage;
    }

    @Override
    public Page<AgentResourceLogVO> queryResourceLogPage(int page, int size, String resourceType, String status) {
        Page<AgentResourceLog> logPage = new Page<>(page, size);
        LambdaQueryWrapper<AgentResourceLog> wrapper = new LambdaQueryWrapper<>();
        if (resourceType != null && !resourceType.trim().isEmpty()) {
            wrapper.eq(AgentResourceLog::getResourceType, resourceType);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(AgentResourceLog::getStatus, status);
        }
        wrapper.orderByDesc(AgentResourceLog::getCreateTime);

        resourceLogMapper.selectPage(logPage, wrapper);

        Page<AgentResourceLogVO> voPage = new Page<>(page, size, logPage.getTotal());
        List<AgentResourceLog> records = logPage.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        // 批量关联 User 名字
        Set<Long> userIds = records.stream().map(AgentResourceLog::getUserId).collect(Collectors.toSet());
        Map<Long, String> userMap = getUserNamesMap(userIds);

        List<AgentResourceLogVO> voRecords = records.stream().map(po -> {
            AgentResourceLogVO vo = new AgentResourceLogVO();
            vo.setId(po.getId());
            vo.setSpaceId(po.getSpaceId());
            vo.setPlanId(po.getPlanId());
            vo.setPhaseIndex(po.getPhaseIndex());
            vo.setResourceType(po.getResourceType());
            vo.setTitle(po.getTitle());
            vo.setStatus(po.getStatus());
            vo.setResultUrl(po.getResultUrl());
            vo.setGenerationParams(po.getGenerationParams());
            vo.setRetryCount(po.getRetryCount());
            vo.setErrorMessage(po.getErrorMessage());
            vo.setUserId(po.getUserId());
            vo.setUserName(userMap.getOrDefault(po.getUserId(), "未知用户"));
            vo.setCreateTime(po.getCreateTime());
            vo.setUpdateTime(po.getUpdateTime());
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voRecords);
        return voPage;
    }

    @Override
    public Page<KnowledgeFileJobVO> queryRAGJobPage(int page, int size, String status) {
        Page<KnowledgeFileJob> jobPage = new Page<>(page, size);
        LambdaQueryWrapper<KnowledgeFileJob> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(KnowledgeFileJob::getStatus, status);
        }
        wrapper.orderByDesc(KnowledgeFileJob::getCreatedAt);

        fileJobMapper.selectPage(jobPage, wrapper);

        Page<KnowledgeFileJobVO> voPage = new Page<>(page, size, jobPage.getTotal());
        List<KnowledgeFileJob> records = jobPage.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        // 批量关联 User 名字，知识库名，文件名，防止大量关联导致数据库瓶颈
        Set<Long> userIds = records.stream().map(KnowledgeFileJob::getUserId).collect(Collectors.toSet());
        Set<Long> kbIds = records.stream().map(KnowledgeFileJob::getKnowledgeBaseId).collect(Collectors.toSet());
        Set<Long> fileIds = records.stream().map(KnowledgeFileJob::getFileId).collect(Collectors.toSet());

        Map<Long, String> userMap = getUserNamesMap(userIds);
        Map<Long, String> kbMap = getKbNamesMap(kbIds);
        Map<Long, String> fileMap = getFileNamesMap(fileIds);

        List<KnowledgeFileJobVO> voRecords = records.stream().map(po -> {
            KnowledgeFileJobVO vo = new KnowledgeFileJobVO();
            vo.setId(po.getId());
            vo.setUserId(po.getUserId());
            vo.setUserName(userMap.getOrDefault(po.getUserId(), "未知用户"));
            vo.setKnowledgeBaseId(po.getKnowledgeBaseId());
            vo.setKnowledgeBaseName(kbMap.getOrDefault(po.getKnowledgeBaseId(), "未知知识库"));
            vo.setFileId(po.getFileId());
            vo.setFileName(fileMap.getOrDefault(po.getFileId(), "未知文件"));
            vo.setJobType(po.getJobType());
            vo.setStatus(po.getStatus());
            vo.setProgress(po.getProgress());
            vo.setCurrentStep(po.getCurrentStep());
            vo.setErrorMessage(po.getErrorMessage());
            vo.setRetryCount(po.getRetryCount());
            vo.setStartedAt(po.getStartedAt());
            vo.setFinishedAt(po.getFinishedAt());
            vo.setCreatedAt(po.getCreatedAt());
            vo.setUpdatedAt(po.getUpdatedAt());
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voRecords);
        return voPage;
    }

    @Override
    public boolean retryRAGJob(Long jobId) {
        KnowledgeFileJob job = fileJobMapper.selectById(jobId);
        if (job == null) {
            return false;
        }
        // 重置任务属性为等待执行状态
        job.setStatus("pending");
        job.setProgress(0);
        job.setErrorMessage(null);
        job.setCurrentStep("等待调度重新解析");
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setRetryCount(0);
        job.setUpdatedAt(LocalDateTime.now());
        
        fileJobMapper.updateById(job);
        log.info("用户重置并重新触发 RAG 异步解析任务成功，Job ID: {}", jobId);
        return true;
    }

    /**
     * 辅助方法：批量获取用户名 Map
     */
    private Map<Long, String> getUserNamesMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        List<User> users = userMapper.selectList(new QueryWrapper<User>().in("id", userIds));
        if (users == null) return Collections.emptyMap();
        return users.stream().collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    /**
     * 辅助方法：批量获取知识库名称 Map
     */
    private Map<Long, String> getKbNamesMap(Set<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return Collections.emptyMap();
        List<KnowledgeBase> kbs = baseMapper.selectList(new QueryWrapper<KnowledgeBase>().in("id", kbIds));
        if (kbs == null) return Collections.emptyMap();
        return kbs.stream().collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
    }

    /**
     * 辅助方法：批量获取文件名 Map
     */
    private Map<Long, String> getFileNamesMap(Set<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return Collections.emptyMap();
        List<KnowledgeFile> files = fileMapper.selectList(new QueryWrapper<KnowledgeFile>().in("id", fileIds));
        if (files == null) return Collections.emptyMap();
        return files.stream().collect(Collectors.toMap(KnowledgeFile::getId, KnowledgeFile::getOriginalName, (a, b) -> a));
    }
}
