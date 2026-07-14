package com.smartorder.edu.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 代码题测试用例 Mapper
 *
 * @author 马强
 * @since 2026-07-11
 */
@Mapper
public interface EvaluationCodeTestCaseMapper extends BaseMapper<EvaluationCodeTestCase> {

    /** 批量插入测试用例 */
    int insertBatch(@Param("list") List<EvaluationCodeTestCase> testCases);
}
