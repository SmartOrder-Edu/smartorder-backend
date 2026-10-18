package com.smartorder.edu.student.codejudge;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码判题结果
 *
 * @author 马强
 * @since 2026-07-11
 */
@Data
@Builder
public class CodeJudgeResult {

    /** 整体判题状态 */
    private CodeJudgeStatus status;

    /** 实际得分 */
    private BigDecimal score;

    /** 满分 */
    private BigDecimal maxScore;

    /** 是否通过全部用例 */
    private Boolean accepted;

    /** 编译错误信息 */
    private String compileMessage;

    /** 运行时错误信息 */
    private String runtimeMessage;

    /** 各用例判题详情 */
    private List<CodeJudgeCaseResult> cases;

    /** 判题执行来源，用于前端区分 docker / judge0 等执行器 */
    private String gradedBy;

    /**
     * 生成写入 evaluation_answer_record.judgeResult 的 JSON Map
     */
    public Map<String, Object> toJudgeResultJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status != null ? status.name() : CodeJudgeStatus.SE.name());
        map.put("score", score != null ? score : BigDecimal.ZERO);
        map.put("maxScore", maxScore != null ? maxScore : BigDecimal.ZERO);
        map.put("accepted", accepted != null ? accepted : false);
        map.put("compileMessage", compileMessage != null ? compileMessage : "");
        map.put("runtimeMessage", runtimeMessage != null ? runtimeMessage : "");
        map.put("gradedBy", gradedBy != null && !gradedBy.isBlank() ? gradedBy : "docker-code-judge");

        // 汇总统计
        Map<String, Object> summary = new LinkedHashMap<>();
        int total = cases != null ? cases.size() : 0;
        int passed = 0;
        int samplePassed = 0;
        int hiddenPassed = 0;
        if (cases != null) {
            for (CodeJudgeCaseResult c : cases) {
                if (c.getStatus() == CodeJudgeStatus.AC) {
                    passed++;
                    if (c.isSample()) {
                        samplePassed++;
                    } else {
                        hiddenPassed++;
                    }
                }
            }
        }
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("samplePassed", samplePassed);
        summary.put("hiddenPassed", hiddenPassed);
        map.put("summary", summary);

        // 各用例结果（通过 toSafeMap 保护隐藏用例数据）
        List<Map<String, Object>> caseList = new ArrayList<>();
        if (cases != null) {
            for (CodeJudgeCaseResult c : cases) {
                caseList.add(c.toSafeMap());
            }
        }
        map.put("cases", caseList);

        return map;
    }

    /**
     * 创建编译错误结果
     */
    public static CodeJudgeResult compileError(String message, BigDecimal maxScore) {
        BigDecimal safeMaxScore = maxScore != null ? maxScore : BigDecimal.ZERO;
        return CodeJudgeResult.builder()
                .status(CodeJudgeStatus.CE)
                .score(BigDecimal.ZERO)
                .maxScore(safeMaxScore)
                .accepted(false)
                .compileMessage(message)
                .runtimeMessage("")
                .cases(new ArrayList<>())
                .build();
    }

    /**
     * 创建系统错误结果
     */
    public static CodeJudgeResult systemError(String message, BigDecimal maxScore) {
        BigDecimal safeMaxScore = maxScore != null ? maxScore : BigDecimal.ZERO;
        return CodeJudgeResult.builder()
                .status(CodeJudgeStatus.SE)
                .score(BigDecimal.ZERO)
                .maxScore(safeMaxScore)
                .accepted(false)
                .compileMessage("")
                .runtimeMessage(message)
                .cases(new ArrayList<>())
                .build();
    }

    /**
     * 按权重计算得分：maxScore × passedWeight / totalWeight
     */
    public static BigDecimal calculateScore(List<CodeJudgeCaseResult> caseResults, BigDecimal maxScore) {
        if (caseResults == null || caseResults.isEmpty() || maxScore == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal passedWeight = BigDecimal.ZERO;

        for (CodeJudgeCaseResult cr : caseResults) {
            // 权重默认 1.00（从测试用例中获取，此处简化处理）
            BigDecimal weight = BigDecimal.ONE;
            totalWeight = totalWeight.add(weight);
            if (cr.getStatus() == CodeJudgeStatus.AC) {
                passedWeight = passedWeight.add(weight);
            }
        }

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return maxScore.multiply(passedWeight)
                .divide(totalWeight, 2, RoundingMode.HALF_UP);
    }
}
