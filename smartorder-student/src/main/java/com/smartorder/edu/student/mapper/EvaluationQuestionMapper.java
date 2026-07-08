package com.smartorder.edu.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvaluationQuestionMapper extends BaseMapper<EvaluationQuestion> {

    /**
     * 按生成任务ID查询已成功生成的题目列表，按序号排序。
     * <p>显式指定 JSON 字段的 JacksonTypeHandler，因为自定义 @Select 不会自动继承
     * {@code autoResultMap = true} 的 typeHandler 配置。</p>
     *
     * @param taskId 生成任务ID
     * @return 题目实体列表
     */
    @Select("SELECT q.* FROM evaluation_question_generation_item i "
            + "INNER JOIN evaluation_question q ON i.question_id = q.id "
            + "WHERE i.task_id = #{taskId} AND i.status = 'success' "
            + "ORDER BY i.sequence_no ASC")
    @Results({
            @Result(property = "options", column = "options", typeHandler = JacksonTypeHandler.class),
            @Result(property = "correctAnswer", column = "correct_answer", typeHandler = JacksonTypeHandler.class),
            @Result(property = "commonMistakes", column = "common_mistakes", typeHandler = JacksonTypeHandler.class),
            @Result(property = "tags", column = "tags", typeHandler = JacksonTypeHandler.class)
    })
    List<EvaluationQuestion> selectByTaskId(@Param("taskId") Long taskId);
}
