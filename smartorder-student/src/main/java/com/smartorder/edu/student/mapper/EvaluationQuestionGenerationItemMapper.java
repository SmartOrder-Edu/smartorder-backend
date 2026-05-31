package com.smartorder.edu.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EvaluationQuestionGenerationItemMapper extends BaseMapper<EvaluationQuestionGenerationItem> {

    int insertBatch(@Param("list") List<EvaluationQuestionGenerationItem> items);
}
