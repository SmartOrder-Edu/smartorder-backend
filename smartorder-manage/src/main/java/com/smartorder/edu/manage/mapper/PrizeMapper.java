package com.smartorder.edu.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.manage.domain.po.Prize;
import org.apache.ibatis.annotations.Mapper;

/**
 * 奖品数据库持久层映射接口
 */
@Mapper
public interface PrizeMapper extends BaseMapper<Prize> {
}
