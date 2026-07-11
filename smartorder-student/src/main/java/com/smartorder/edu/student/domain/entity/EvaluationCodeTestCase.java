package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 代码题测试用例实体
 *
 * @author 马强
 * @since 2026-07-11
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_code_test_case")
public class EvaluationCodeTestCase {

    /** 测试用例ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 题目ID */
    private Long questionId;

    /** 标准输入 */
    private String input;

    /** 期望输出 */
    private String expectedOutput;

    /** 是否样例：0否，1是 */
    private Integer isSample;

    /** 权重 */
    private BigDecimal weight;

    /** 时间限制(ms) */
    private Integer timeLimitMs;

    /** 内存限制(MB) */
    private Integer memoryLimitMb;

    /** 排序 */
    private Integer sortOrder;

    /** 状态：1启用，0禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
