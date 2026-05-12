package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.entity.UserFileQuota;
import com.smartorder.edu.student.mapper.UserFileQuotaMapper;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserFileQuotaServiceImpl implements IUserFileQuotaService {

    private final UserFileQuotaMapper userFileQuotaMapper;

    @Override
    public UserFileQuota getQuota(Long userId) {
        UserFileQuota quota = userFileQuotaMapper.selectById(userId);
        if (quota == null) {
            return initQuota(userId);
        }
        return quota;
    }

    @Override
    public UserFileQuota initQuota(Long userId) {
        UserFileQuota quota = new UserFileQuota();
        quota.setUserId(userId);
        quota.setUsedSpace(0L);
        quota.setVersion(0);
        userFileQuotaMapper.insert(quota);
        return quota;
    }

    @Override
    public boolean addUsedSpace(Long userId, Long bytes) {
        UserFileQuota quota = getQuota(userId);
        UpdateWrapper<UserFileQuota> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("version", quota.getVersion())
                .set("used_space", quota.getUsedSpace() + bytes)
                .set("version", quota.getVersion() + 1);
        int rows = userFileQuotaMapper.update(null, wrapper);
        if (rows == 0) {
            log.warn("乐观锁更新失败，用户{}空间增加{}字节", userId, bytes);
            return false;
        }
        return true;
    }

    @Override
    public boolean reduceUsedSpace(Long userId, Long bytes) {
        UserFileQuota quota = getQuota(userId);
        if (quota.getUsedSpace() < bytes) {
            throw new BusinessException("已用空间不足，无法减少");
        }
        UpdateWrapper<UserFileQuota> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("version", quota.getVersion())
                .set("used_space", quota.getUsedSpace() - bytes)
                .set("version", quota.getVersion() + 1);
        int rows = userFileQuotaMapper.update(null, wrapper);
        if (rows == 0) {
            log.warn("乐观锁更新失败，用户{}空间减少{}字节", userId, bytes);
            return false;
        }
        return true;
    }
}
