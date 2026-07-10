package com.smartorder.edu.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.manage.domain.po.AgentToolExecution;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体工具执行审计 Mapper 接口
 */
@Mapper
public interface AgentToolExecutionMapper extends BaseMapper<AgentToolExecution> {
}
