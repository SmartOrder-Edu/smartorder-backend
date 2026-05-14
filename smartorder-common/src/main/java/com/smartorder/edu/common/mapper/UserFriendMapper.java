package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.UserFriend;
import org.apache.ibatis.annotations.Mapper;


/**
 * 好友申请表 Mapper 接口
 */
@Mapper
public interface UserFriendMapper extends BaseMapper<UserFriend> {
}
