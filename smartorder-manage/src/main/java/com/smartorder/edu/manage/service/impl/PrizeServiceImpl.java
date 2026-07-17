package com.smartorder.edu.manage.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.manage.domain.dto.PrizeCreateDTO;
import com.smartorder.edu.manage.domain.dto.PrizeQueryDTO;
import com.smartorder.edu.manage.domain.po.Prize;
import com.smartorder.edu.manage.domain.po.Feedback;
import com.smartorder.edu.manage.domain.vo.PrizeVO;
import com.smartorder.edu.manage.mapper.PrizeMapper;
import com.smartorder.edu.manage.mapper.FeedbackMapper;
import com.smartorder.edu.manage.service.PrizeService;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.model.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 奖品卡密服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrizeServiceImpl extends ServiceImpl<PrizeMapper, Prize> implements PrizeService {

    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;

    @Override
    public Page<PrizeVO> queryPrizePage(PrizeQueryDTO queryDTO) {
        Page<Prize> pageParam = new Page<>(queryDTO.getPage(), queryDTO.getSize());
        
        // 索引 idx_status_name 完美覆盖此组合检索
        Page<Prize> poPage = this.page(pageParam, Wrappers.lambdaQuery(Prize.class)
                .like(queryDTO.getName() != null && !queryDTO.getName().trim().isEmpty(), Prize::getName, queryDTO.getName())
                .eq(queryDTO.getStatus() != null, Prize::getStatus, queryDTO.getStatus())
                .orderByDesc(Prize::getCreateTime));

        Page<PrizeVO> voPage = new Page<>(poPage.getCurrent(), poPage.getSize(), poPage.getTotal());
        
        List<Prize> records = poPage.getRecords();
        if (records == null || records.isEmpty()) {
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }

        // 提取 userIds 和 feedbackIds 进行批量查询
        Set<Long> userIds = records.stream()
                .map(Prize::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> feedbackIds = records.stream()
                .map(Prize::getFeedbackId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(userIds);
            if (users != null) {
                userMap = users.stream().collect(Collectors.toMap(User::getId, User::getUsername, (k1, k2) -> k1));
            }
        }

        Map<Long, String> feedbackMap = new HashMap<>();
        if (!feedbackIds.isEmpty()) {
            List<Feedback> feedbacks = feedbackMapper.selectBatchIds(feedbackIds);
            if (feedbacks != null) {
                feedbackMap = feedbacks.stream().collect(Collectors.toMap(
                        Feedback::getId,
                        f -> f.getTitle() != null && !f.getTitle().trim().isEmpty() 
                                ? f.getTitle() 
                                : f.getContent().substring(0, Math.min(f.getContent().length(), 15)) + "...",
                        (k1, k2) -> k1
                ));
            }
        }

        final Map<Long, String> finalUserMap = userMap;
        final Map<Long, String> finalFeedbackMap = feedbackMap;
        List<PrizeVO> voList = records.stream()
                .map(po -> convertToVO(po, finalUserMap, finalFeedbackMap))
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createPrizes(PrizeCreateDTO createDTO) {
        LocalDateTime now = LocalDateTime.now();
        List<Prize> poList = createDTO.getPrizeCodes().stream()
                .map(code -> {
                    Prize po = new Prize();
                    po.setName(createDTO.getName());
                    po.setPrizeCode(code.trim());
                    po.setStatus(0); // 0=未发放
                    po.setCreateTime(now);
                    po.setUpdateTime(now);
                    return po;
                }).collect(Collectors.toList());
        
        // 批量极速保存
        this.saveBatch(poList);
        log.info("管理员成功批量录入奖品卡密，名称: {}, 数量: {}", createDTO.getName(), poList.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discardPrize(Long id) {
        Prize po = this.getById(id);
        if (po == null) {
            throw new IllegalArgumentException("奖品卡密不存在");
        }
        if (po.getStatus() != 0) {
            throw new IllegalStateException("仅未发放的卡密才允许作废");
        }
        po.setStatus(2); // 2=已作废
        po.setUpdateTime(LocalDateTime.now());
        this.updateById(po);
        log.info("管理员成功作废了奖品卡密，ID: {}, 码值: {}", id, po.getPrizeCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String allocatePrize(Long feedbackId, Long userId) {
        // 核心高并发安全调优：利用 MySQL 行锁 SELECT ... LIMIT 1 FOR UPDATE 排他读取
        // 杜绝了多线程并发争抢时产生卡密被重复超卖派发的严重 Bug
        List<Prize> list = this.list(Wrappers.lambdaQuery(Prize.class)
                .eq(Prize::getStatus, 0) // 未发放
                .orderByAsc(Prize::getId) // 按入库最早排队
                .last("LIMIT 1 FOR UPDATE")); // MySQL 排他行锁

        if (list == null || list.isEmpty()) {
            log.warn("奖品兑换码卡密库存已经全部派空！用户ID: {}, 反馈ID: {}", userId, feedbackId);
            return null; // 库存告罄
        }

        Prize prize = list.get(0);
        prize.setStatus(1); // 1=已发放
        prize.setUserId(userId);
        prize.setFeedbackId(feedbackId);
        prize.setAllocateTime(LocalDateTime.now());
        prize.setUpdateTime(LocalDateTime.now());
        
        this.updateById(prize);
        
        log.info("成功为用户发放了奖品卡密，用户ID: {}, 奖品名称: {}, 兑换码: {}", userId, prize.getName(), prize.getPrizeCode());
        return prize.getPrizeCode();
    }

    @Override
    public Map<String, Long> getPrizeStats() {
        // 高性能 SQL 看板聚合查询，在 MySQL 层面 group by 聚合统计，瞬间返回
        QueryWrapper<Prize> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("status", "COUNT(*) as cnt")
                .groupBy("status");

        List<Map<String, Object>> maps = this.listMaps(queryWrapper);

        long availableInventory = 0;
        long allocatedCount = 0;
        long discardedCount = 0;

        for (Map<String, Object> map : maps) {
            Integer status = (Integer) map.get("status");
            Long count = ((Number) map.get("cnt")).longValue();
            if (status == 0) availableInventory = count;
            else if (status == 1) allocatedCount = count;
            else if (status == 2) discardedCount = count;
        }

        Map<String, Long> stats = new HashMap<>();
        stats.put("availableInventory", availableInventory);
        stats.put("allocatedCount", allocatedCount);
        stats.put("discardedCount", discardedCount);
        return stats;
    }

    private PrizeVO convertToVO(Prize po) {
        return convertToVO(po, null, null);
    }

    private PrizeVO convertToVO(Prize po, Map<Long, String> userMap, Map<Long, String> feedbackMap) {
        PrizeVO vo = new PrizeVO();
        BeanUtil.copyProperties(po, vo);
        
        // 关联展示用：用户名回显
        if (po.getUserId() != null) {
            if (userMap != null && userMap.containsKey(po.getUserId())) {
                vo.setUsername(userMap.get(po.getUserId()));
            } else {
                User user = userMapper.selectById(po.getUserId());
                if (user != null) {
                    vo.setUsername(user.getUsername());
                }
            }
        }

        // 关联展示用：反馈标题回显
        if (po.getFeedbackId() != null) {
            if (feedbackMap != null && feedbackMap.containsKey(po.getFeedbackId())) {
                vo.setFeedbackTitle(feedbackMap.get(po.getFeedbackId()));
            } else {
                Feedback feedback = feedbackMapper.selectById(po.getFeedbackId());
                if (feedback != null) {
                    vo.setFeedbackTitle(feedback.getTitle() != null && !feedback.getTitle().trim().isEmpty() 
                            ? feedback.getTitle() 
                            : feedback.getContent().substring(0, Math.min(feedback.getContent().length(), 15)) + "...");
                }
            }
        }
        return vo;
    }
}
