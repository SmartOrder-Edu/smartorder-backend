package com.smartorder.edu.student.skill;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 题目 Skill 注册中心。
 *
 * <p>启动时扫描 classpath:skills/questions/ 目录，解析所有 SKILL.md 并缓存元数据。
 * 只缓存轻量元数据（{@link QuestionSkillMetadata}），不缓存正文——正文由
 * {@link QuestionSkillLoader} 按需加载。
 *
 * <p>线程安全：初始化后运行时只读，使用 {@link ConcurrentHashMap} 防御性编程。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Slf4j
@Component
public class QuestionSkillRegistry {

    private static final String SKILLS_BASE_PATH = "skills/questions/";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final QuestionSkillParser parser;

    /** 说明书 Skill（启动时必须存在） */
    @Getter
    private ParsedQuestionSkill metaSkill;

    /** questionType → 题型 Skill 的解析结果（含正文，供 Loader 使用） */
    private final Map<String, ParsedQuestionSkill> parsedTypeSkills = new ConcurrentHashMap<>();

    /** questionType → 题型 Skill 的轻量元数据（对外暴露） */
    private final Map<String, QuestionSkillMetadata> typeMetadata = new ConcurrentHashMap<>();

    public QuestionSkillRegistry(QuestionSkillParser parser) {
        this.parser = parser;
    }

    /**
     * 启动时扫描并解析所有 SKILL.md。
     */
    @PostConstruct
    public void init() {
        log.info("[QuestionSkillRegistry] 开始扫描 Skill 目录: {}", SKILLS_BASE_PATH);
        long start = System.currentTimeMillis();

        try {
            List<SkillFileInfo> files = scanSkillFiles();

            for (SkillFileInfo file : files) {
                try {
                    String content = readClasspathFile(file.path);
                    ParsedQuestionSkill parsed = parser.parse(file.directoryName, content);

                    if (parsed.isMeta()) {
                        metaSkill = parsed;
                        log.info("[QuestionSkillRegistry] 注册说明书 Skill: key={}, hash={}",
                                parsed.skillKey(), parsed.contentHash());
                    } else {
                        String questionType = parsed.manifest().getQuestionType();
                        parsedTypeSkills.put(questionType, parsed);
                        typeMetadata.put(questionType, QuestionSkillMetadata.from(parsed));
                        log.info("[QuestionSkillRegistry] 注册题型 Skill: type={}, key={}, score={}",
                                questionType, parsed.skillKey(), parsed.manifest().getDefaultScore());
                    }
                } catch (Exception e) {
                    log.error("[QuestionSkillRegistry] Skill 解析失败，跳过: path={}", file.path, e);
                }
            }

            validate();

            log.info("[QuestionSkillRegistry] 扫描完成: meta={}, typeSkills={}, 耗时 {}ms",
                    metaSkill != null ? "已加载" : "缺失",
                    typeMetadata.size(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[QuestionSkillRegistry] Skill 目录扫描失败", e);
            throw new IllegalStateException("题目 Skill 初始化失败: " + e.getMessage(), e);
        }
    }

    // ======================== 查询接口 ========================

    /** 按题型编码获取轻量元数据 */
    public Optional<QuestionSkillMetadata> getMetadata(String questionType) {
        return Optional.ofNullable(typeMetadata.get(questionType));
    }

    /** 获取所有已注册的题型编码 */
    public Set<String> allQuestionTypes() {
        return Collections.unmodifiableSet(typeMetadata.keySet());
    }

    /** 获取所有题型元数据 */
    public Collection<QuestionSkillMetadata> allMetadata() {
        return Collections.unmodifiableCollection(typeMetadata.values());
    }

    /** 判断题型是否已注册 */
    public boolean contains(String questionType) {
        return typeMetadata.containsKey(questionType);
    }

    /** 获取题型数量 */
    public int typeCount() {
        return typeMetadata.size();
    }

    /**
     * 题型编码 → Skill 目录名的映射（来自说明书 YAML）。
     * 例如：{@code single_choice → single-choice}
     */
    public Map<String, String> questionTypeMapping() {
        if (metaSkill == null || metaSkill.manifest().getQuestionTypeMapping() == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(metaSkill.manifest().getQuestionTypeMapping());
    }

    /**
     * 目录名 → 题型编码 的反向映射。
     */
    public Map<String, String> directoryToTypeMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var entry : questionTypeMapping().entrySet()) {
            mapping.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(mapping);
    }

    // ======================== 内部方法 ========================

    /** 供 Loader 内部使用的已解析 Skill */
    ParsedQuestionSkill getParsedTypeSkill(String questionType) {
        return parsedTypeSkills.get(questionType);
    }

    /**
     * 使用 Spring PathMatchingResourcePatternResolver 扫描
     * classpath:skills/questions/ 下所有 SKILL.md。
     */
    private List<SkillFileInfo> scanSkillFiles() throws IOException {
        List<SkillFileInfo> result = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Resource[] resources = resolver.getResources(
                "classpath:" + SKILLS_BASE_PATH + "**/" + SKILL_FILE_NAME);

        for (Resource resource : resources) {
            String path = resource.getURL().getPath();
            String relativePath = path.substring(path.indexOf(SKILLS_BASE_PATH));
            String dirName = extractDirectoryName(relativePath);
            result.add(new SkillFileInfo(relativePath, dirName));
        }

        return result;
    }

    /**
     * 从相对路径提取目录名。
     * <pre>
     *   "skills/questions/single-choice/SKILL.md" → "single-choice"
     *   "skills/questions/SKILL.md"               → "questions"
     * </pre>
     */
    private String extractDirectoryName(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        String[] parts = normalized.split("/");
        // parts: ["skills", "questions", "single-choice", "SKILL.md"] → "single-choice"
        // parts: ["skills", "questions", "SKILL.md"]                  → "questions"
        if (parts.length >= 4) {
            return parts[parts.length - 2];
        }
        return parts[parts.length - 1].replace(".md", "");
    }

    /** 读取 classpath 文件内容 */
    private String readClasspathFile(String path) throws IOException {
        Resource resource = new org.springframework.core.io.ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 启动校验 */
    private void validate() {
        if (metaSkill == null) {
            throw new IllegalStateException(
                    "缺少说明书 Skill: " + SKILLS_BASE_PATH + SKILL_FILE_NAME);
        }
        if (typeMetadata.isEmpty()) {
            log.warn("[QuestionSkillRegistry] 未注册任何题型 Skill，目录可能为空: {}",
                    SKILLS_BASE_PATH);
        }
        // 校验说明书中的映射表与实际注册的题型是否一致
        Map<String, String> mapping = metaSkill.manifest().getQuestionTypeMapping();
        if (mapping != null) {
            for (var entry : mapping.entrySet()) {
                String questionType = entry.getKey();
                String dirName = entry.getValue();
                if (!typeMetadata.containsKey(questionType)) {
                    log.warn("[QuestionSkillRegistry] 说明书映射了未注册的题型: {} → {}",
                            questionType, dirName);
                }
            }
        }
    }

    /** 文件信息 */
    private record SkillFileInfo(String path, String directoryName) {}
}
