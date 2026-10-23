package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IResourcePackageService {

    ResourcePackageVO previewPackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto);

    ResourcePackageVO generatePackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto);

    void streamGeneratePackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto, SseEmitter emitter);
}
