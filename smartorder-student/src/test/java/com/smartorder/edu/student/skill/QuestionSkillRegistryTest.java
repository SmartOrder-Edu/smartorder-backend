package com.smartorder.edu.student.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QuestionSkillRegistry} 单元测试——基于真实 classpath 文件。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
class QuestionSkillRegistryTest {

    /**
     * 创建已初始化的 Registry（每个测试独立），确保 SKILL.md 文件正确解析。
     */
    private static QuestionSkillRegistry newInitializedRegistry() {
        QuestionSkillRegistry registry = new QuestionSkillRegistry(new QuestionSkillParser());
        registry.init();
        return registry;
    }

    @Test
    @DisplayName("启动扫描真实 classpath 文件，正确注册说明书和 9 个题型")
    void shouldScanAndRegisterAllSkillsFromClasspath() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        // 说明书必须存在（skillKey 来自目录名，isMeta() 基于 YAML name 字段）
        assertNotNull(registry.getMetaSkill());
        assertTrue(registry.getMetaSkill().isMeta());
        assertEquals("question-generation", registry.getMetaSkill().manifest().getName());

        // 9 种题型全部注册
        assertEquals(9, registry.typeCount());
        Set<String> expectedTypes = Set.of(
                "single_choice", "multiple_choice", "fill_blank",
                "judge", "code", "sql", "scenario",
                "short_answer", "multimodal");
        assertEquals(expectedTypes, registry.allQuestionTypes());

        // 验证映射表
        Map<String, String> mapping = registry.questionTypeMapping();
        assertEquals(9, mapping.size());
        assertEquals("single-choice", mapping.get("single_choice"));
        assertEquals("multiple-choice", mapping.get("multiple_choice"));
        assertEquals("fill-blank", mapping.get("fill_blank"));
        assertEquals("judge", mapping.get("judge"));
        assertEquals("code", mapping.get("code"));
        assertEquals("sql", mapping.get("sql"));
        assertEquals("scenario", mapping.get("scenario"));
        assertEquals("short-answer", mapping.get("short_answer"));
        assertEquals("multimodal", mapping.get("multimodal"));
    }

    @Test
    @DisplayName("getMetadata 按题型编码返回正确元数据")
    void shouldGetMetadataByQuestionType() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        Optional<QuestionSkillMetadata> meta = registry.getMetadata("single_choice");
        assertTrue(meta.isPresent());
        assertEquals("single_choice", meta.get().questionType());
        assertEquals("single-choice", meta.get().skillKey());
        assertEquals(5, meta.get().defaultScore().intValue());
        assertTrue(meta.get().objective());
        assertFalse(meta.get().autoInvoke());
    }

    @Test
    @DisplayName("getMetadata 查询未知题型返回 Optional.empty()")
    void shouldReturnEmptyForUnknownType() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        Optional<QuestionSkillMetadata> meta = registry.getMetadata("unknown_type");
        assertTrue(meta.isEmpty());
    }

    @Test
    @DisplayName("contains 正确判断题型是否存在")
    void shouldCheckContainsCorrectly() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        assertTrue(registry.contains("single_choice"));
        assertTrue(registry.contains("code"));
        assertFalse(registry.contains("nonexistent"));
    }

    @Test
    @DisplayName("directoryToTypeMapping 返回正确的反向映射")
    void shouldReturnCorrectDirectoryToTypeMapping() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        Map<String, String> reverse = registry.directoryToTypeMapping();
        assertEquals("single_choice", reverse.get("single-choice"));
        assertEquals("multiple_choice", reverse.get("multiple-choice"));
        assertEquals("fill_blank", reverse.get("fill-blank"));
    }

    @Test
    @DisplayName("说明书映射的题型与实际注册的题型一致")
    void shouldHaveConsistentMappingAndRegistry() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        Map<String, String> mapping = registry.questionTypeMapping();
        for (String type : mapping.keySet()) {
            assertTrue(registry.contains(type),
                    "映射表中的 " + type + " 应已注册");
        }
    }

    @Test
    @DisplayName("init() 完成后 typeCount 返回正确的题型数量")
    void shouldReturnCorrectTypeCount() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        assertEquals(9, registry.typeCount());
    }

    @Test
    @DisplayName("getParsedTypeSkill 返回已解析的 Skill 正文")
    void shouldReturnParsedTypeSkillInternally() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        ParsedQuestionSkill parsed = registry.getParsedTypeSkill("single_choice");
        assertNotNull(parsed);
        assertEquals("single-choice", parsed.skillKey());
        assertNotNull(parsed.body());
        assertFalse(parsed.body().isEmpty());
    }

    @Test
    @DisplayName("未注册的题型 getParsedTypeSkill 返回 null")
    void shouldReturnNullForUnregisteredType() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        ParsedQuestionSkill parsed = registry.getParsedTypeSkill("nonexistent");
        assertNull(parsed);
    }

    @Test
    @DisplayName("allQuestionTypes 返回不可修改的集合")
    void shouldReturnUnmodifiableQuestionTypes() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        Set<String> types = registry.allQuestionTypes();
        assertThrows(UnsupportedOperationException.class, () -> types.add("new_type"));
    }

    @Test
    @DisplayName("allMetadata 返回所有已注册题型的元数据")
    void shouldReturnAllMetadata() {
        QuestionSkillRegistry registry = newInitializedRegistry();

        assertEquals(9, registry.allMetadata().size());
    }
}
