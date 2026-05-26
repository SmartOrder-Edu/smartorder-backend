package com.smartorder.edu.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.manage.domain.po.Feedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 反馈 Mapper 接口
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
