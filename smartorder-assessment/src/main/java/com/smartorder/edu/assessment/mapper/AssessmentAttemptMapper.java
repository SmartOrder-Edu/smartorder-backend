package com.smartorder.edu.assessment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.assessment.domain.entity.AssessmentAttempt;
import org.apache.ibatis.annotations.Mapper;

/**
 * @deprecated 全仓无任何调用方，对应实体 {@link AssessmentAttempt} 也未被使用。
 * 作答轮次现由 {@code EvaluationSessionMapper} 承载。本接口可在确认后连同实体一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Mapper
public interface AssessmentAttemptMapper extends BaseMapper<AssessmentAttempt> {
}
