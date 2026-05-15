package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户Mapper接口，提供对用户表的数据库操作
 * @author 谢光湘
 * @since 2026/2/26
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {


}
