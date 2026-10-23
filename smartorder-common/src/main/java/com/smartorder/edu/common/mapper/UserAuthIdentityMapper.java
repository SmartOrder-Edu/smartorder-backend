package com.smartorder.edu.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.common.model.entity.UserAuthIdentity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户第三方登录身份 Mapper
 * @author Ma Qiang
 * @since 2026/7/14
 */
@Mapper
public interface UserAuthIdentityMapper extends BaseMapper<UserAuthIdentity> {
}
