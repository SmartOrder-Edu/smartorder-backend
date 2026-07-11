package com.smartorder.edu.student.codejudge;

/**
 * 代码判题服务接口
 *
 * @author 马强
 * @since 2026-07-11
 */
public interface ICodeJudgeService {

    /**
     * 判题
     *
     * @param request 判题请求
     * @return 判题结果
     */
    CodeJudgeResult judge(CodeJudgeRequest request);
}
