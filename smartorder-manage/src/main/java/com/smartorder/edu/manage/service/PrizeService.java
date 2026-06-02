package com.smartorder.edu.manage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartorder.edu.manage.domain.dto.PrizeCreateDTO;
import com.smartorder.edu.manage.domain.dto.PrizeQueryDTO;
import com.smartorder.edu.manage.domain.po.Prize;
import com.smartorder.edu.manage.domain.vo.PrizeVO;

import java.util.Map;

/**
 * 奖品卡密服务接口
 */
public interface PrizeService extends IService<Prize> {

    /**
     * 分页检索奖品总账卡密列表 (管理端)
     */
    Page<PrizeVO> queryPrizePage(PrizeQueryDTO queryDTO);

    /**
     * 批量导入/生成新的兑换码卡密
     */
    void createPrizes(PrizeCreateDTO createDTO);

    /**
     * 废弃或者下线指定的未发放卡密 (状态 2)
     */
    void discardPrize(Long id);

    /**
     * 原子安全行锁分配出库一个未发放的卡密给获得者
     * @param feedbackId 反馈ID
     * @param userId 获得人ID
     * @return 分配成功的卡密兑换码
     */
    String allocatePrize(Long feedbackId, Long userId);

    /**
     * 获取管理端卡密看板真实统计指标 (可用库存、累计已发放、已作废数)
     */
    Map<String, Long> getPrizeStats();
}
