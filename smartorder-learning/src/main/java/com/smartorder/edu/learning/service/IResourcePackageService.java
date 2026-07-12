package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.ResourcePackageVO;
import com.smartorder.edu.learning.dto.ResourcePackageGenerateDTO;

public interface IResourcePackageService {

    ResourcePackageVO previewPackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto);

    ResourcePackageVO generatePackage(Long userId, Long spaceId, ResourcePackageGenerateDTO dto);
}
