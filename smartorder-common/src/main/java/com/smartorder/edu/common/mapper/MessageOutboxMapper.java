package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.MessageOutbox;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutbox> {
}
