package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Resource;

import java.util.List;

public interface IResourceService {

    List<Resource> getBySpaceId(Long spaceId);
}
