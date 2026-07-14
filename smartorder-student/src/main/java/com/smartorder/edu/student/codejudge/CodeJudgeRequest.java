package com.smartorder.edu.student.codejudge;

import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 代码判题请求
 *
 * @author 马强
 * @since 2026-07-11
 */
@Data
@Builder
public class CodeJudgeRequest {

    /** 题目ID */
    private Long questionId;

    /** 编程语言：c11 / cpp17 */
    private String language;

    /** 源代码 */
    private String sourceCode;

    /** 满分值 */
    private BigDecimal maxScore;

    /** 测试用例列表 */
    private List<EvaluationCodeTestCase> testCases;

    /** 时间限制(ms)，null 则使用默认值 */
    private Integer timeLimitMs;

    /** 内存限制(MB)，null 则使用默认值 */
    private Integer memoryLimitMb;
}
