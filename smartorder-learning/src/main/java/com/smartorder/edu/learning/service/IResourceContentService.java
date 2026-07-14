package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.ResourceContent;

import java.util.List;

public interface IResourceContentService {

    ResourceContent getLatestByResourceId(Long resourceId);

    ResourceContent createContent(Long resourceId, String contentType, String content, String reviewResult);

    List<ResourceContent> batchCreate(List<ResourceContent> contents);
}
