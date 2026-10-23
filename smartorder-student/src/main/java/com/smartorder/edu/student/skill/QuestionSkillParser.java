package com.smartorder.edu.student.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SKILL.md 文件解析器。
 *
 * <p>解析流程：
 * <ol>
 *   <li>检查文件是否以 "---" 开头</li>
 *   <li>找到两个 "---" 之间的 YAML 文本</li>
 *   <li>用 Jackson YAML 反序列化为 {@link QuestionSkillManifest}</li>
 *   <li>取出第二个 "---" 之后的 Markdown 正文</li>
 *   <li>对全文计算 SHA-256</li>
 * </ol>
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Slf4j
@Component
public class QuestionSkillParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * 解析 SKILL.md 文件全部内容。
     *
     * @param directoryName SKILL.md 所在目录名，作为 skillKey
     * @param content       SKILL.md 文件全文
     * @return 解析结果
     * @throws IllegalArgumentException 如果文件不以 "---" 开头或格式不完整
     */
    public ParsedQuestionSkill parse(String directoryName, String content) {
        if (content == null || !content.strip().startsWith("---")) {
            throw new IllegalArgumentException(
                    "SKILL.md 必须以 YAML frontmatter (---) 开头: " + directoryName);
        }

        // 1. 定位 YAML 边界
        int firstLineEnd = content.indexOf('\n');
        if (firstLineEnd < 0) {
            throw new IllegalArgumentException(
                    "SKILL.md YAML frontmatter 格式不完整: " + directoryName);
        }

        int closing = content.indexOf("\n---", firstLineEnd);
        if (closing < 0) {
            // 兼容 \r\n 换行符
            closing = content.indexOf("\r\n---", firstLineEnd);
        }
        if (closing < 0) {
            throw new IllegalArgumentException(
                    "SKILL.md 缺少 YAML frontmatter 结束标记 ---: " + directoryName);
        }

        // 2. 提取 YAML
        String yaml = content.substring(firstLineEnd + 1, closing).trim();

        // 3. 提取正文：结束标记 "---" 之后的内容
        int bodyStart = content.indexOf('\n', closing + 1);
        if (bodyStart < 0) {
            bodyStart = closing + 3;
        }
        String body = content.substring(bodyStart + 1).trim();

        // 4. 反序列化 YAML → QuestionSkillManifest
        QuestionSkillManifest manifest;
        try {
            manifest = YAML_MAPPER.readValue(yaml, QuestionSkillManifest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "SKILL.md YAML 解析失败: " + directoryName + " — " + e.getMessage(), e);
        }

        // 5. 计算 SHA-256
        String contentHash = sha256(content);

        String skillKey = normalizeKey(directoryName);

        log.debug("[QuestionSkillParser] 解析完成: skillKey={}, type={}, hash={}, bodyLength={}",
                skillKey, manifest.getQuestionType(), contentHash, body.length());

        return new ParsedQuestionSkill(skillKey, manifest, body, contentHash);
    }

    /**
     * 规范化目录名为 skillKey：去除首尾空白、转小写、空格转横线。
     */
    private static String normalizeKey(String directoryName) {
        return directoryName.trim().toLowerCase().replaceAll("\\s+", "-");
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     */
    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
