package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.UserFileQuota;

public interface IUserFileQuotaService {
    UserFileQuota getQuota(Long userId);
    void initQuota(Long userId);
    boolean addUsedSpace(Long userId, Long bytes);
    boolean reduceUsedSpace(Long userId, Long bytes);
}
