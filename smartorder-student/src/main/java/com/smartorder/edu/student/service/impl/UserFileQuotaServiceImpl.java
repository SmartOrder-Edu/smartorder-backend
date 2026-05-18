package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    // 这里设置一个合理的最大空间限制，例如10GB
    private static final long MAX_TOTAL_SPACE = 10L * 1024 * 1024 * 1024; // 10GB

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
        quota.setUserSpace(0L);
        quota.setVersion(0);
        userFileQuotaMapper.insert(quota);
        return quota;
    }

    @Override
    public boolean addUserSpace(Long userId, Long bytes) {
        UserFileQuota quota = getQuota(userId);
        long newUsedSpace = quota.getUserSpace() + bytes;
        if (newUsedSpace > MAX_TOTAL_SPACE) {
            throw new BusinessException("存储空间不足，已用" + quota.getUserSpace() + "字节，上限10GB，本次上传" + bytes + "字节");
        }
        LambdaUpdateWrapper<UserFileQuota> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserFileQuota::getUserId, userId)
                .eq(UserFileQuota::getVersion, quota.getVersion())
                .set(UserFileQuota::getUserSpace, newUsedSpace)
                .set(UserFileQuota::getVersion, quota.getVersion() + 1);
        int rows = userFileQuotaMapper.update(null, wrapper);
        if (rows == 0) {
            log.warn("乐观锁更新失败，用户{}空间增加{}字节", userId, bytes);
            return false;
        }
        return true;
    }

    @Override
    public boolean reduceUserSpace(Long userId, Long bytes) {
        UserFileQuota quota = getQuota(userId);
        if (quota.getUserSpace() < bytes) {
            throw new BusinessException("已用空间不足，无法减少");
        }
        LambdaUpdateWrapper<UserFileQuota> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserFileQuota::getUserId, userId)
                .eq(UserFileQuota::getVersion, quota.getVersion())
                .set(UserFileQuota::getUserSpace, quota.getUserSpace() - bytes)
                .set(UserFileQuota::getVersion, quota.getVersion() + 1);
        int rows = userFileQuotaMapper.update(null, wrapper);
        if (rows == 0) {
            log.warn("乐观锁更新失败，用户{}空间减少{}字节", userId, bytes);
            return false;
        }
        return true;
    }
}
