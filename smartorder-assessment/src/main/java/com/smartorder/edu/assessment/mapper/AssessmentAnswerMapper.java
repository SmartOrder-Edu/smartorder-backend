package com.smartorder.edu.assessment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.assessment.domain.entity.AssessmentAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * @deprecated 全仓无任何调用方，对应实体 {@link AssessmentAnswer} 也未被使用。
 * 作答记录现由 {@code EvaluationAnswerRecordMapper} 承载。本接口可在确认后连同实体一起删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Mapper
public interface AssessmentAnswerMapper extends BaseMapper<AssessmentAnswer> {
}
