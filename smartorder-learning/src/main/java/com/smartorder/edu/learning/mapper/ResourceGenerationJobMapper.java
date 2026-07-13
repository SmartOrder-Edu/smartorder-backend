package com.smartorder.edu.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.learning.domain.entity.ResourceGenerationJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceGenerationJobMapper extends BaseMapper<ResourceGenerationJob> {

    @Select("""
            SELECT * FROM resource_generation_job
            WHERE space_id = #{spaceId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<ResourceGenerationJob> selectRecentBySpaceId(@Param("spaceId") Long spaceId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM resource_generation_job
            WHERE resource_id = #{resourceId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT 1
            """)
    ResourceGenerationJob selectLatestByResourceId(@Param("resourceId") Long resourceId);
}
