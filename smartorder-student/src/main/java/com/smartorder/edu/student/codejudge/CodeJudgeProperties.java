package com.smartorder.edu.student.codejudge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码判题配置属性
 *
 * @author 马强
 * @since 2026-07-11
 */
@Data
@Component
@ConfigurationProperties(prefix = "smartorder.code-judge")
public class CodeJudgeProperties {

    /** 是否启用代码判题 */
    private boolean enabled = true;

    /** 判题服务提供方：docker 或 judge0 */
    private String provider = "docker";

    /** 判题工作目录 */
    private String workDir = "./target/code-judge-work";

    /** C++17 Docker 镜像 */
    private String dockerImageCpp17 = "smartorder-code-runner:cpp17";

    /** C11 Docker 镜像 */
    private String dockerImageC11 = "smartorder-code-runner:c11";

    /** 默认时间限制(ms) */
    private int defaultTimeLimitMs = 1000;

    /** 默认内存限制(MB) */
    private int defaultMemoryLimitMb = 128;

    /** 最大源码长度(字节) */
    private int maxSourceCodeLength = 65535;

    /** 最大输出长度(字符) */
    private int maxOutputLength = 8192;

    /** Judge0 服务地址，例如：http://127.0.0.1:2358 */
    private String judge0BaseUrl = "http://127.0.0.1:2358";

    /** Judge0 鉴权令牌，私有部署未开启鉴权时可留空 */
    private String judge0AuthToken = "";

    /** Judge0 请求/响应是否使用 Base64，默认开启以保护中文、换行和特殊字符 */
    private boolean judge0Base64Encoded = true;

    /** Judge0 轮询间隔，单位毫秒 */
    private int judge0PollIntervalMs = 500;

    /** Judge0 单次判题轮询超时，单位毫秒 */
    private int judge0PollTimeoutMs = 10000;

    /** Judge0 语言映射，可通过配置覆盖以适配 CE/Extra CE 或私有镜像 */
    private Map<String, Integer> judge0LanguageMap = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("c", 50),
            Map.entry("c11", 50),
            Map.entry("cpp", 54),
            Map.entry("cpp17", 54),
            Map.entry("java", 62),
            Map.entry("python", 71),
            Map.entry("python3", 71)
    ));

    /**
     * GCC 编译器路径前缀。留空则使用系统 PATH 中的 g++/gcc。
     * CentOS 7 安装 devtoolset 后需设置，例如：
     * /opt/rh/devtoolset-11/root/usr/bin/
     */
    private String gccPrefix = "";

    /**
     * 本地原生运行模式（不依赖 Docker，直接在本机执行编译产物）。
     * 仅适用于本地开发调试，生产环境必须关闭。
     */
    private boolean nativeMode = false;
}
