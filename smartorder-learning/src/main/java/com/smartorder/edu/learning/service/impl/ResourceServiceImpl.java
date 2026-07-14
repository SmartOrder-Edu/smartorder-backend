package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
    public Resource getById(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        QueryWrapper<Resource> qw = new QueryWrapper<>();
        qw.eq("id", resourceId).eq("is_deleted", 0).last("LIMIT 1");
        return resourceMapper.selectOne(qw);
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

    @Override
    public List<Resource> batchInsertGenerated(List<Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        for (Resource r : resources) {
            r.setStatus("generated");
            r.setSource(r.getSource() == null ? "ai" : r.getSource());
            r.setIsDeleted(r.getIsDeleted() == null ? 0 : r.getIsDeleted());
            r.setIsAiGenerated(r.getIsAiGenerated() == null ? 1 : r.getIsAiGenerated());
            if (r.getUrl() == null) {
                r.setUrl("");
            }
            resourceMapper.insert(r);
        }
        log.info("[Resource] batchInsertGenerated: 已插入 {} 条已生成资源", resources.size());
        return resources;
    }

    @Override
    public void updateStatus(Long resourceId, String status) {
        UpdateWrapper<Resource> uw = new UpdateWrapper<>();
        uw.eq("id", resourceId).eq("is_deleted", 0)
                .set("status", status);
        resourceMapper.update(null, uw);
    }

    @Override
    public void markGenerated(Long resourceId, String fileFormat) {
        UpdateWrapper<Resource> uw = new UpdateWrapper<>();
        uw.eq("id", resourceId).eq("is_deleted", 0)
                .set("status", "generated");
        if (fileFormat != null && !fileFormat.isBlank()) {
            uw.set("file_format", fileFormat);
        }
        resourceMapper.update(null, uw);
    }
}
