package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.mapper.ResourceMapper;
import com.smartorder.edu.learning.service.IResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceServiceImpl implements IResourceService {

    private final ResourceMapper resourceMapper;

    @Override
    public List<Resource> getBySpaceId(Long spaceId) {
        QueryWrapper<Resource> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("is_deleted", 0).orderByDesc("create_time");
        return resourceMapper.selectList(qw);
    }

    @Override
    public List<Resource> batchInsertPlanned(List<Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        for (Resource r : resources) {
            if (r.getStatus() == null) {
                r.setStatus("planned");
            }
            if (r.getIsDeleted() == null) {
                r.setIsDeleted(0);
            }
            if (r.getIsAiGenerated() == null) {
                r.setIsAiGenerated(0);
            }
            // url NOT NULL 兜底（草案阶段没有真实 URL）
            if (r.getUrl() == null) {
                r.setUrl("");
            }
            resourceMapper.insert(r);
        }
        log.info("[Resource] batchInsertPlanned: 已插入 {} 条计划中资源", resources.size());
        return resources;
    }
}
