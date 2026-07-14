package com.smartorder.edu.student.codejudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DockerCodeJudgeServiceImpl 单元测试
 * <p>测试 normalize、aggregateStatus、calculateScoreByWeight 等纯逻辑方法</p>
 */
class DockerCodeJudgeServiceImplTest {

    // 手动创建实例以测试 private/package-private 方法
    private final CodeJudgeProperties properties;

    DockerCodeJudgeServiceImplTest() {
        properties = new CodeJudgeProperties();
        // 使用默认值
    }

    private DockerCodeJudgeServiceImpl createService() {
        return new DockerCodeJudgeServiceImpl(properties);
    }

    // ==================== normalize() ====================

    @Test
    @DisplayName("normalize: 基础输出无变化")
    void normalize_plainText_unchanged() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("hello world");
        assertEquals("hello world", result);
    }

    @Test
    @DisplayName("normalize: 去除行尾空格")
    void normalize_stripTrailingSpaces() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("hello   \nworld  ");
        assertEquals("hello\nworld", result);
    }

    @Test
    @DisplayName("normalize: CRLF→LF 转换")
    void normalize_crlfToLf() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("line1\r\nline2\r\nline3");
        assertEquals("line1\nline2\nline3", result);
    }

    @Test
    @DisplayName("normalize: 去除尾部空行")
    void normalize_removeTrailingEmptyLines() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("output\n\n\n");
        assertEquals("output", result);
    }

    @Test
    @DisplayName("normalize: 空字符串")
    void normalize_emptyString() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("");
        assertEquals("", result);
    }

    @Test
    @DisplayName("normalize: null 输入")
    void normalize_nullInput() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize(null);
        assertEquals("", result);
    }

    @Test
    @DisplayName("normalize: 保留中间空行")
    void normalize_preserveInnerEmptyLines() {
        DockerCodeJudgeServiceImpl service = createService();
        String result = service.normalize("a\n\nb\n\n");
        assertEquals("a\n\nb", result);
    }

    @Test
    @DisplayName("normalize: 多行输出比对正确")
    void normalize_multilineMatch() {
        DockerCodeJudgeServiceImpl service = createService();
        String expected = "1\n2\n3";
        String actual = "1  \r\n2  \r\n3  \r\n";
        assertEquals(
                service.normalize(expected),
                service.normalize(actual));
    }

    // ==================== aggregateStatus() ====================

    @Test
    @DisplayName("aggregateStatus: 全部通过 → AC")
    void aggregateStatus_allAC() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true),
                acCase(2L, false),
                acCase(3L, false));
        assertEquals(CodeJudgeStatus.AC, service.aggregateStatus(cases, false));
    }

    @Test
    @DisplayName("aggregateStatus: 有 WA → WA")
    void aggregateStatus_hasWA() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true),
                waCase(2L, false));
        assertEquals(CodeJudgeStatus.WA, service.aggregateStatus(cases, false));
    }

    @Test
    @DisplayName("aggregateStatus: 有 TLE → TLE（优先级高于 WA）")
    void aggregateStatus_hasTLE() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true),
                waCase(2L, false),
                tleCase(3L, false));
        assertEquals(CodeJudgeStatus.TLE, service.aggregateStatus(cases, false));
    }

    @Test
    @DisplayName("aggregateStatus: 有 RE → RE")
    void aggregateStatus_hasRE() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true),
                reCase(2L, false));
        assertEquals(CodeJudgeStatus.RE, service.aggregateStatus(cases, false));
    }

    @Test
    @DisplayName("aggregateStatus: 有 MLE → MLE")
    void aggregateStatus_hasMLE() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true),
                mleCase(2L, false));
        assertEquals(CodeJudgeStatus.MLE, service.aggregateStatus(cases, false));
    }

    @Test
    @DisplayName("aggregateStatus: 空列表 → SE")
    void aggregateStatus_emptyList() {
        DockerCodeJudgeServiceImpl service = createService();
        assertEquals(CodeJudgeStatus.SE, service.aggregateStatus(Collections.emptyList(), false));
    }

    @Test
    @DisplayName("aggregateStatus: null → SE")
    void aggregateStatus_null() {
        DockerCodeJudgeServiceImpl service = createService();
        assertEquals(CodeJudgeStatus.SE, service.aggregateStatus(null, false));
    }

    @Test
    @DisplayName("aggregateStatus: 编译失败 → CE")
    void aggregateStatus_compileFailed() {
        DockerCodeJudgeServiceImpl service = createService();
        assertEquals(CodeJudgeStatus.CE, service.aggregateStatus(Collections.emptyList(), true));
    }

    // ==================== calculateScoreByWeight() ====================

    @Test
    @DisplayName("calculateScore: 全部通过 → 满分")
    void calculateScore_allPass() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true), acCase(2L, true), acCase(3L, true));
        BigDecimal result = service.calculateScoreByWeight(cases, new BigDecimal("15.00"));
        assertEquals(0, new BigDecimal("15.00").compareTo(result));
    }

    @Test
    @DisplayName("calculateScore: 50% 通过 → 50% 分")
    void calculateScore_halfPass() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                acCase(1L, true), waCase(2L, false));
        BigDecimal result = service.calculateScoreByWeight(cases, new BigDecimal("10.00"));
        assertEquals(0, new BigDecimal("5.00").compareTo(result));
    }

    @Test
    @DisplayName("calculateScore: 全部错误 → 0 分")
    void calculateScore_allFail() {
        DockerCodeJudgeServiceImpl service = createService();
        List<CodeJudgeCaseResult> cases = Arrays.asList(
                waCase(1L, true), reCase(2L, false));
        BigDecimal result = service.calculateScoreByWeight(cases, new BigDecimal("15.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(result));
    }

    @Test
    @DisplayName("calculateScore: null/空 → 0")
    void calculateScore_nullOrEmpty() {
        DockerCodeJudgeServiceImpl service = createService();
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calculateScoreByWeight(null, new BigDecimal("10.00"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calculateScoreByWeight(Collections.emptyList(), new BigDecimal("10.00"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calculateScoreByWeight(Arrays.asList(acCase(1L, true)), null)));
    }

    // ==================== CodeJudgeResult 工厂方法 ====================

    @Test
    @DisplayName("CodeJudgeResult.compileError: 状态=CE, 分数=0")
    void compileErrorResult() {
        CodeJudgeResult result = CodeJudgeResult.compileError("syntax error", new BigDecimal("15.00"));
        assertEquals(CodeJudgeStatus.CE, result.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getScore()));
        assertEquals("syntax error", result.getCompileMessage());
        assertFalse(result.getAccepted());
    }

    @Test
    @DisplayName("CodeJudgeResult.systemError: 状态=SE, 分数=0")
    void systemErrorResult() {
        CodeJudgeResult result = CodeJudgeResult.systemError("docker crashed", new BigDecimal("15.00"));
        assertEquals(CodeJudgeStatus.SE, result.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getScore()));
        assertEquals("docker crashed", result.getRuntimeMessage());
        assertFalse(result.getAccepted());
    }

    // ==================== CodeJudgeResult.toJudgeResultJson() ====================

    @Test
    @DisplayName("toJudgeResultJson: 包含所有必要字段")
    void toJudgeResultJson_fields() {
        CodeJudgeCaseResult case1 = CodeJudgeCaseResult.builder()
                .caseId(1L).sample(true).status(CodeJudgeStatus.AC).timeMs(10).memoryKb(1024)
                .actualOutput("hello").exitCode(0).build();
        CodeJudgeCaseResult case2 = CodeJudgeCaseResult.builder()
                .caseId(2L).sample(false).status(CodeJudgeStatus.AC).timeMs(5).memoryKb(512)
                .actualOutput("secret").exitCode(0).build();

        CodeJudgeResult result = CodeJudgeResult.builder()
                .status(CodeJudgeStatus.AC).score(new BigDecimal("10.00"))
                .maxScore(new BigDecimal("10.00")).accepted(true)
                .compileMessage("").runtimeMessage("")
                .cases(Arrays.asList(case1, case2)).build();

        java.util.Map<String, Object> json = result.toJudgeResultJson();
        assertEquals("AC", json.get("status"));
        assertNotNull(json.get("cases"));
        assertNotNull(json.get("summary"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> summary = (java.util.Map<String, Object>) json.get("summary");
        assertEquals(2, summary.get("total"));
        assertEquals(2, summary.get("passed"));
        assertEquals(1, summary.get("samplePassed"));
        assertEquals(1, summary.get("hiddenPassed"));
        assertEquals("docker-code-judge", json.get("gradedBy"));
    }

    @Test
    @DisplayName("toJudgeResultJson: 隐藏用例不暴露 actualOutput")
    void toJudgeResultJson_hiddenCaseNoOutput() {
        CodeJudgeCaseResult hiddenCase = CodeJudgeCaseResult.builder()
                .caseId(99L).sample(false).status(CodeJudgeStatus.WA).timeMs(100).memoryKb(2048)
                .actualOutput("secret answer").exitCode(0).build();

        CodeJudgeResult result = CodeJudgeResult.builder()
                .status(CodeJudgeStatus.WA).score(BigDecimal.ZERO)
                .maxScore(new BigDecimal("15.00")).accepted(false)
                .compileMessage("").runtimeMessage("")
                .cases(List.of(hiddenCase)).build();

        java.util.Map<String, Object> json = result.toJudgeResultJson();

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> cases = (java.util.List<java.util.Map<String, Object>>) json.get("cases");
        java.util.Map<String, Object> hiddenCaseJson = cases.get(0);
        // 隐藏用例的 actualOutput 应为 null
        assertNull(hiddenCaseJson.get("actualOutput"));
    }

    // ==================== 辅助方法 ====================

    private CodeJudgeCaseResult acCase(Long id, boolean sample) {
        return CodeJudgeCaseResult.builder()
                .caseId(id).sample(sample).status(CodeJudgeStatus.AC)
                .timeMs(10).memoryKb(1024).actualOutput("ok").exitCode(0).build();
    }

    private CodeJudgeCaseResult waCase(Long id, boolean sample) {
        return CodeJudgeCaseResult.builder()
                .caseId(id).sample(sample).status(CodeJudgeStatus.WA)
                .timeMs(10).memoryKb(1024).actualOutput("wrong").exitCode(0).build();
    }

    private CodeJudgeCaseResult tleCase(Long id, boolean sample) {
        return CodeJudgeCaseResult.builder()
                .caseId(id).sample(sample).status(CodeJudgeStatus.TLE)
                .timeMs(3000).memoryKb(1024).actualOutput("").exitCode(-1).build();
    }

    private CodeJudgeCaseResult reCase(Long id, boolean sample) {
        return CodeJudgeCaseResult.builder()
                .caseId(id).sample(sample).status(CodeJudgeStatus.RE)
                .timeMs(5).memoryKb(512).actualOutput("error").exitCode(1).build();
    }

    private CodeJudgeCaseResult mleCase(Long id, boolean sample) {
        return CodeJudgeCaseResult.builder()
                .caseId(id).sample(sample).status(CodeJudgeStatus.MLE)
                .timeMs(100).memoryKb(131072).actualOutput("").exitCode(137).build();
    }
}
