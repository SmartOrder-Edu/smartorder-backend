package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartorder.edu.student.domain.entity.UserFileQuota;
import com.smartorder.edu.student.mapper.UserFileQuotaMapper;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
            try {
                return initQuota(userId);
            } catch (DuplicateKeyException e) {
                // 并发首次访问时另一个线程已经插入，回查即可
                UserFileQuota existing = userFileQuotaMapper.selectById(userId);
                if (existing == null) {
                    throw new IllegalStateException("云盘配额初始化失败，userId=" + userId);
                }
                return existing;
            }
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

    /**
     * 累加已用空间。
     * <p>
     * 用单条原子 UPDATE 完成「累加 + 上限校验 + 并发控制」，
     * 取代原来的「读 → 算 → where version=? 更新」：
     * 后者在并发场景下大量更新失败，而调用方往往忽略返回值，导致已用空间被静默漏记。
     *
     * @return false 表示用户不存在或累加后会超过 10GB 上限
     */
    @Override
    public boolean addUserSpace(Long userId, Long bytes) {
        if (bytes == null || bytes <= 0) {
            return true;
        }
        // 保证配额行存在（老用户或首次上传时可能还没有记录）
        getQuota(userId);

        LambdaUpdateWrapper<UserFileQuota> wrapper = new LambdaUpdateWrapper<>();
        wrapper.setSql("user_space = user_space + " + bytes + ", version = IFNULL(version, 0) + 1")
                .eq(UserFileQuota::getUserId, userId)
                .apply("user_space + {0} <= {1}", bytes, MAX_TOTAL_SPACE);
        boolean success = userFileQuotaMapper.update(null, wrapper) > 0;
        if (!success) {
            log.warn("用户{}空间累加失败（可能已超出10GB上限），本次: {}字节", userId, bytes);
        }
        return success;
    }

    /**
     * 扣减已用空间，同样是单条原子 UPDATE（{@code user_space >= bytes} 作为更新条件）。
     *
     * @return false 表示用户不存在或已用空间不足以扣减
     */
    @Override
    public boolean reduceUserSpace(Long userId, Long bytes) {
        if (bytes == null || bytes <= 0) {
            return true;
        }
        getQuota(userId);

        LambdaUpdateWrapper<UserFileQuota> wrapper = new LambdaUpdateWrapper<>();
        wrapper.setSql("user_space = user_space - " + bytes + ", version = IFNULL(version, 0) + 1")
                .eq(UserFileQuota::getUserId, userId)
                .apply("user_space >= {0}", bytes);
        boolean success = userFileQuotaMapper.update(null, wrapper) > 0;
        if (!success) {
            log.warn("用户{}空间扣减失败（已用空间不足），本次: {}字节", userId, bytes);
        }
        return success;
    }
}
