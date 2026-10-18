package com.smartorder.edu.student.codejudge;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Docker 代码判题服务实现
 * <p>
 * 宿主机编译（静态链接），Docker 容器运行，ProcessBuilder 调用 docker CLI。
 *
 * @author 马强
 * @since 2026-07-11
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "smartorder.code-judge", name = "provider", havingValue = "docker", matchIfMissing = true)
@RequiredArgsConstructor
public class DockerCodeJudgeServiceImpl implements ICodeJudgeService {

    private final CodeJudgeProperties properties;

    /** 编译超时（秒） */
    private static final int COMPILE_TIMEOUT_SECONDS = 30;

    /** 容器额外超时（ms），在用例时间限制之外增加 */
    private static final int CONTAINER_OVERHEAD_MS = 3000;

    /** 支持的编程语言 */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("c11", "cpp17");

    @Override
    public CodeJudgeResult judge(CodeJudgeRequest request) {
        validateRequest(request);

        String uuid = UUID.randomUUID().toString();
        Path workDir = null;
        try {
            workDir = createWorkDir(uuid);
            log.info("代码判题开始: questionId={}, language={}, workDir={}", request.getQuestionId(), request.getLanguage(), workDir);

            // 1. 写源文件
            Path sourceFile = writeSourceFile(workDir, request.getLanguage(), request.getSourceCode());

            // 2. 编译（宿主机）
            String compileError;
            try {
                compileError = compile(workDir, request.getLanguage(), sourceFile);
            } catch (CompileFailedException e) {
                log.info("编译失败: {}", e.getMessage());
                return CodeJudgeResult.compileError(e.getMessage(), request.getMaxScore());
            }

            // 3. 逐用例运行
            int timeLimitMs = request.getTimeLimitMs() != null ? request.getTimeLimitMs() : properties.getDefaultTimeLimitMs();
            int memoryLimitMb = request.getMemoryLimitMb() != null ? request.getMemoryLimitMb() : properties.getDefaultMemoryLimitMb();
            String dockerImage = getDockerImage(request.getLanguage());
            boolean nativeMode = properties.isNativeMode();

            List<CodeJudgeCaseResult> caseResults = new ArrayList<>();
            String runtimeError = null;

            for (EvaluationCodeTestCase tc : request.getTestCases()) {
                try {
                    CodeJudgeCaseResult caseResult;
                    if (nativeMode) {
                        caseResult = runNativeTestCase(workDir, tc, timeLimitMs);
                    } else {
                        caseResult = runTestCase(workDir, tc, timeLimitMs, memoryLimitMb, dockerImage);
                    }
                    caseResults.add(caseResult);
                } catch (BusinessException e) {
                    throw e; // Docker 不可用，直接抛出
                } catch (Exception e) {
                    log.error("运行用例失败: caseId={}", tc.getId(), e);
                    caseResults.add(CodeJudgeCaseResult.builder()
                            .caseId(tc.getId())
                            .sample(tc.getIsSample() != null && tc.getIsSample() == 1)
                            .status(CodeJudgeStatus.RE)
                            .timeMs(0)
                            .memoryKb(0)
                            .actualOutput(e.getMessage())
                            .exitCode(-1)
                            .build());
                    if (runtimeError == null) {
                        runtimeError = e.getMessage();
                    }
                }
            }

            // 4. 汇总
            CodeJudgeStatus overallStatus = aggregateStatus(caseResults, false);
            BigDecimal score = calculateScoreByWeight(caseResults, request.getMaxScore());
            boolean accepted = overallStatus == CodeJudgeStatus.AC;

            log.info("判题完成: questionId={}, status={}, score={}/{}, passed={}/{}",
                    request.getQuestionId(), overallStatus, score, request.getMaxScore(),
                    caseResults.stream().filter(c -> c.getStatus() == CodeJudgeStatus.AC).count(),
                    caseResults.size());

            return CodeJudgeResult.builder()
                    .status(overallStatus)
                    .score(score)
                    .maxScore(request.getMaxScore())
                    .accepted(accepted)
                    .compileMessage(compileError != null ? compileError : "")
                    .runtimeMessage(runtimeError != null ? runtimeError : "")
                    .cases(caseResults)
                    .build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("代码判题系统异常: questionId={}", request.getQuestionId(), e);
            return CodeJudgeResult.systemError(e.getMessage() != null ? e.getMessage() : "未知系统错误", request.getMaxScore());
        } finally {
            cleanup(workDir);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 校验请求参数
     */
    private void validateRequest(CodeJudgeRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException("当前环境未启用代码判题");
        }
        if (request.getLanguage() == null || !SUPPORTED_LANGUAGES.contains(request.getLanguage())) {
            throw new BusinessException("不支持的编程语言: " + request.getLanguage() + "，支持: " + SUPPORTED_LANGUAGES);
        }
        if (request.getSourceCode() == null || request.getSourceCode().isBlank()) {
            throw new BusinessException("源代码不能为空");
        }
        if (request.getSourceCode().length() > properties.getMaxSourceCodeLength()) {
            throw new BusinessException("源代码过长，最大允许 " + properties.getMaxSourceCodeLength() + " 字符");
        }
        if (request.getTestCases() == null || request.getTestCases().isEmpty()) {
            throw new BusinessException("题目未配置测试用例");
        }
    }

    /**
     * 创建临时工作目录
     */
    private Path createWorkDir(String uuid) throws IOException {
        Path basePath = Paths.get(properties.getWorkDir());
        if (!Files.exists(basePath)) {
            Files.createDirectories(basePath);
        }
        Path workDir = basePath.resolve(uuid);
        Files.createDirectories(workDir);
        return workDir;
    }

    /**
     * 写入源文件
     */
    private Path writeSourceFile(Path workDir, String language, String sourceCode) throws IOException {
        String fileName = "cpp17".equals(language) ? "Main.cpp" : "Main.c";
        Path sourceFile = workDir.resolve(fileName);
        Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);
        return sourceFile;
    }

    /**
     * 宿主机编译。成功返回 null，失败抛 CompileFailedException
     */
    private String compile(Path workDir, String language, Path sourceFile) throws CompileFailedException {
        String prefix = properties.getGccPrefix() != null ? properties.getGccPrefix() : "";
        List<String> cmd = new ArrayList<>();
        if ("cpp17".equals(language)) {
            cmd.addAll(List.of(prefix + "g++", "-std=c++17", "-O2", "-pipe", "-static", "-s",
                    sourceFile.getFileName().toString(), "-o", "main"));
        } else {
            cmd.addAll(List.of(prefix + "gcc", "-std=c11", "-O2", "-pipe", "-static", "-s",
                    sourceFile.getFileName().toString(), "-o", "main"));
        }

        log.debug("编译命令: {}", String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取编译输出
            String output = readStreamToString(process.getInputStream(), properties.getMaxOutputLength());
            boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new CompileFailedException("编译超时（" + COMPILE_TIMEOUT_SECONDS + "秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new CompileFailedException(output != null ? output : "编译失败，退出码: " + exitCode);
            }

            // 编译成功，检查可执行文件是否存在
            Path executable = workDir.resolve("main");
            if (!Files.exists(executable) || !Files.isExecutable(executable)) {
                throw new CompileFailedException("编译产物不存在或不可执行");
            }

            return null;
        } catch (CompileFailedException e) {
            throw e;
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("g++") || msg.contains("gcc") || msg.contains("No such file"))) {
                throw new CompileFailedException("编译环境不可用，未找到 g++/gcc 编译器，请确认已安装 GCC 工具链");
            }
            throw new CompileFailedException("编译异常: " + (msg != null ? msg : "未知错误"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompileFailedException("编译被中断");
        }
    }

    /**
     * 原生运行单个测试用例（不依赖 Docker，直接在宿主机执行编译产物）。
     * <p>仅用于本地开发调试，不提供容器级安全隔离。</p>
     */
    private CodeJudgeCaseResult runNativeTestCase(Path workDir, EvaluationCodeTestCase tc,
                                                   int timeLimitMs) throws Exception {
        boolean isSample = tc.getIsSample() != null && tc.getIsSample() == 1;
        String workDirAbs = workDir.toAbsolutePath().toString();

        ProcessBuilder pb = new ProcessBuilder(workDirAbs + "/main");
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        Process process = pb.start();

        // 写入标准输入
        if (tc.getInput() != null && !tc.getInput().isEmpty()) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(tc.getInput().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        // 超时控制（原生模式多给 5 秒 overhead）
        int totalTimeoutMs = timeLimitMs + 5000;
        boolean finished = process.waitFor(totalTimeoutMs, TimeUnit.MILLISECONDS);

        long timeMs = System.currentTimeMillis() - startTime;
        int exitCode;
        boolean timedOut = false;

        if (finished) {
            exitCode = process.exitValue();
        } else {
            timedOut = true;
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                exitCode = -1;
            }
        }

        String output = readStreamToString(process.getInputStream(), properties.getMaxOutputLength());

        CodeJudgeStatus caseStatus;
        if (timedOut) {
            caseStatus = CodeJudgeStatus.TLE;
        } else if (exitCode != 0) {
            caseStatus = CodeJudgeStatus.RE;
        } else {
            String normalizedExpected = normalize(tc.getExpectedOutput());
            String normalizedActual = normalize(output);
            if (normalizedExpected.equals(normalizedActual)) {
                caseStatus = CodeJudgeStatus.AC;
            } else {
                caseStatus = CodeJudgeStatus.WA;
            }
        }

        return CodeJudgeCaseResult.builder()
                .caseId(tc.getId())
                .sample(isSample)
                .status(caseStatus)
                .timeMs(timeMs)
                .memoryKb(0)
                .actualOutput(output)
                .exitCode(exitCode)
                .build();
    }

    /**
     * Docker 运行单个测试用例
     */
    private CodeJudgeCaseResult runTestCase(Path workDir, EvaluationCodeTestCase tc,
                                             int timeLimitMs, int memoryLimitMb,
                                             String dockerImage) throws Exception {
        boolean isSample = tc.getIsSample() != null && tc.getIsSample() == 1;
        String workDirAbs = workDir.toAbsolutePath().toString();

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--network=none");
        cmd.add("--memory=" + memoryLimitMb + "m");
        cmd.add("--cpus=1");
        cmd.add("--pids-limit=64");
        cmd.add("--read-only");
        cmd.add("-v");
        cmd.add(workDirAbs + ":/judge:rw");
        cmd.add("-w");
        cmd.add("/judge");
        cmd.add(dockerImage);
        cmd.add("./main");

        log.debug("Docker 命令: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.toLowerCase().contains("docker") || msg.contains("not found") || msg.contains("CreateProcess")) {
                throw new BusinessException("Docker 环境不可用，请检查 Docker 是否已安装并启动");
            }
            if (msg.contains("image") || msg.contains("No such image") || msg.contains("repository does not exist")) {
                throw new BusinessException("代码运行镜像未找到，请联系管理员构建 " + dockerImage + " 镜像");
            }
            throw new BusinessException("启动代码运行容器失败: " + msg);
        }

        // 写入标准输入
        if (tc.getInput() != null && !tc.getInput().isEmpty()) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(tc.getInput().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        // 等待进程完成（带超时）
        int totalTimeoutMs = timeLimitMs + CONTAINER_OVERHEAD_MS;
        boolean finished = process.waitFor(totalTimeoutMs, TimeUnit.MILLISECONDS);

        long timeMs = System.currentTimeMillis() - startTime;
        int exitCode = -1;
        boolean timedOut = false;

        if (finished) {
            exitCode = process.exitValue();
        } else {
            timedOut = true;
            process.destroyForcibly();
            // 等待 destroyForcibly 生效
            process.waitFor(2, TimeUnit.SECONDS);
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // 进程仍未退出
            }
        }

        // 读取输出
        String output = readStreamToString(process.getInputStream(), properties.getMaxOutputLength());

        // 判断用例状态
        CodeJudgeStatus caseStatus;
        if (timedOut) {
            caseStatus = CodeJudgeStatus.TLE;
        } else if (exitCode == 137) {
            // SIGKILL (常见于 OOM)
            caseStatus = CodeJudgeStatus.MLE;
        } else if (exitCode != 0) {
            caseStatus = CodeJudgeStatus.RE;
        } else {
            // 归一化比对
            String normalizedExpected = normalize(tc.getExpectedOutput());
            String normalizedActual = normalize(output);
            if (normalizedExpected.equals(normalizedActual)) {
                caseStatus = CodeJudgeStatus.AC;
            } else {
                caseStatus = CodeJudgeStatus.WA;
            }
        }

        return CodeJudgeCaseResult.builder()
                .caseId(tc.getId())
                .sample(isSample)
                .status(caseStatus)
                .timeMs(timeMs)
                .memoryKb(0) // Docker CLI 模式下难以精确获取内存，留 0
                .actualOutput(output)
                .exitCode(exitCode)
                .build();
    }

    /**
     * 输出归一化：CRLF→LF，行尾去空格，去尾部空行
     */
    String normalize(String output) {
        if (output == null) {
            return "";
        }
        // 统一换行符
        String normalized = output.replace("\r\n", "\n").replace("\r", "\n");

        // 按行处理
        String[] lines = normalized.split("\n", -1);

        // 每行右侧去空格
        for (int i = 0; i < lines.length; i++) {
            lines[i] = rstrip(lines[i]);
        }

        // 去除尾部空行
        int endIdx = lines.length;
        while (endIdx > 0 && lines[endIdx - 1].isEmpty()) {
            endIdx--;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < endIdx; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 字符串右侧去空格（兼容 Java 8）
     */
    private String rstrip(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return s.substring(0, i + 1);
    }

    /**
     * 汇总整体状态
     * <p>优先级: CE > SE > TLE > RE > MLE > WA > AC</p>
     */
    CodeJudgeStatus aggregateStatus(List<CodeJudgeCaseResult> caseResults, boolean compileFailed) {
        if (compileFailed) {
            return CodeJudgeStatus.CE;
        }
        if (caseResults == null || caseResults.isEmpty()) {
            return CodeJudgeStatus.SE;
        }

        boolean hasTLE = false;
        boolean hasRE = false;
        boolean hasMLE = false;
        boolean hasWA = false;
        boolean allAC = true;

        for (CodeJudgeCaseResult cr : caseResults) {
            switch (cr.getStatus()) {
                case TLE:
                    hasTLE = true;
                    allAC = false;
                    break;
                case RE:
                    hasRE = true;
                    allAC = false;
                    break;
                case MLE:
                    hasMLE = true;
                    allAC = false;
                    break;
                case WA:
                    hasWA = true;
                    allAC = false;
                    break;
                case SE:
                    allAC = false;
                    break;
                case AC:
                    break;
                default:
                    allAC = false;
                    break;
            }
        }

        if (allAC) return CodeJudgeStatus.AC;
        if (hasTLE) return CodeJudgeStatus.TLE;
        if (hasMLE) return CodeJudgeStatus.MLE;
        if (hasRE) return CodeJudgeStatus.RE;
        if (hasWA) return CodeJudgeStatus.WA;
        return CodeJudgeStatus.SE;
    }

    /**
     * 按测试用例权重计算得分
     */
    BigDecimal calculateScoreByWeight(List<CodeJudgeCaseResult> caseResults, BigDecimal maxScore) {
        if (caseResults == null || caseResults.isEmpty() || maxScore == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal passedWeight = BigDecimal.ZERO;

        for (CodeJudgeCaseResult cr : caseResults) {
            BigDecimal weight = BigDecimal.ONE; // 默认权重 1
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

    /**
     * 清理工作目录
     */
    private void cleanup(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try {
            deleteRecursively(workDir);
            log.debug("清理工作目录: {}", workDir);
        } catch (IOException e) {
            log.warn("清理工作目录失败: {}", workDir, e);
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 读取流到字符串
     */
    private String readStreamToString(InputStream inputStream, int maxLen) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int totalLen = 0;
            while ((line = reader.readLine()) != null) {
                if (totalLen + line.length() + 1 > maxLen) {
                    sb.append(line, 0, maxLen - totalLen);
                    sb.append("\n...[输出已截断]");
                    break;
                }
                sb.append(line).append('\n');
                totalLen += line.length() + 1;
            }
            return sb.toString();
        }
    }

    /**
     * 根据语言获取 Docker 镜像
     */
    private String getDockerImage(String language) {
        return "cpp17".equals(language) ? properties.getDockerImageCpp17() : properties.getDockerImageC11();
    }

    /**
     * 编译失败异常（内部使用）
     */
    private static class CompileFailedException extends Exception {
        CompileFailedException(String message) {
            super(message);
        }
    }
}
