package com.smartorder.edu.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.learning.domain.entity.Resource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
}
