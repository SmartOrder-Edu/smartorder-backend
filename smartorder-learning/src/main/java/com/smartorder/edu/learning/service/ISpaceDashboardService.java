package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.SpaceDashboardVO;

public interface ISpaceDashboardService {

    SpaceDashboardVO getDashboard(Long userId, Long spaceId);
}
