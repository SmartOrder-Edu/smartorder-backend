package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.UserFileQuota;

public interface IUserFileQuotaService {
    UserFileQuota getQuota(Long userId);
    UserFileQuota initQuota(Long userId);
    boolean addUserSpace(Long userId, Long bytes);
    boolean reduceUserSpace(Long userId, Long bytes);
}
