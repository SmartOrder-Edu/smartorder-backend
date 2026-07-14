package com.smartorder.edu.student.codejudge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个测试用例判题结果
 *
 * @author 马强
 * @since 2026-07-11
 */
@Data
@Builder
@AllArgsConstructor
public class CodeJudgeCaseResult {

    /** 用例ID */
    private Long caseId;

    /** 是否样例用例 */
    private boolean sample;

    /** 用例判题状态 */
    private CodeJudgeStatus status;

    /** 运行耗时(ms) */
    private long timeMs;

    /** 内存使用(KB) */
    private long memoryKb;

    /** 实际输出（隐藏用例对外不暴露） */
    private String actualOutput;

    /** 程序退出码 */
    private int exitCode;

    /**
     * 生成安全的对外返回 Map，隐藏用例不包含输入输出
     */
    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caseId", caseId);
        map.put("sample", sample);
        map.put("status", status.name());
        map.put("timeMs", timeMs);
        map.put("memoryKb", memoryKb);

        // 样例用例附带实际输出，隐藏用例不暴露
        if (sample) {
            map.put("actualOutput", actualOutput);
        }

        if (!sample && status != CodeJudgeStatus.AC) {
            // 隐藏用例失败时仅展示失败状态，不暴露数据
            map.put("actualOutput", null);
        }

        return map;
    }
}
