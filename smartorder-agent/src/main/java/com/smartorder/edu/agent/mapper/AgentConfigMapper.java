package com.smartorder.edu.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.agent.model.entity.AgentConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 旧版 Agent 配置 Mapper。
 *
 * @deprecated 全仓无任何调用方，对应实体 {@link AgentConfig} 也未被使用。
 * 本接口可在确认后连同实体一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfig> {
}
