package com.smartorder.edu.student.codejudge;

import lombok.Getter;

/**
 * 代码判题状态枚举
 *
 * @author 马强
 * @since 2026-07-11
 */
@Getter
public enum CodeJudgeStatus {

    /** 通过 */
    AC("通过"),
    /** 答案错误 */
    WA("答案错误"),
    /** 编译错误 */
    CE("编译错误"),
    /** 运行错误 */
    RE("运行错误"),
    /** 超时 */
    TLE("超时"),
    /** 内存超限 */
    MLE("内存超限"),
    /** 系统错误 */
    SE("系统错误");

    private final String displayName;

    CodeJudgeStatus(String displayName) {
        this.displayName = displayName;
    }
}
