package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.RagQueryLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_query_logs Mapper 接口
 *
 * @deprecated 全仓无任何调用方，对应实体 {@link RagQueryLog} 也未被使用。
 * 保留它只会让 MyBatis 多注册一个无意义的 Mapper。本接口可在确认后连同实体一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Mapper
public interface RagQueryLogMapper extends BaseMapper<RagQueryLog> {

}
