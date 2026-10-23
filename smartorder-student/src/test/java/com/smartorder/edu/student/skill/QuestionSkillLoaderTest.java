package com.smartorder.edu.student.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link QuestionSkillLoader} 单元测试。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@ExtendWith(MockitoExtension.class)
class QuestionSkillLoaderTest {

    @Mock
    private QuestionSkillRegistry registry;

    private QuestionSkillLoader loader;

    private static final String SINGLE_CHOICE_BODY = """
            生成单选题，type 固定为 "单选题"。

            # 题目约束
            1. 每题必须有 {{optionCount}} 个选项。
            2. answer 为单个大写字母字符串。
            """;

    private static final String CODE_BODY = """
            生成编程题，type 固定为 "编程题"。

            约束：
            1. options 必须是空数组 []。
            2. answer 格式：完整的参考答案代码字符串。
            """;

    private static final String META_BODY = """
            # 角色
            你是专业教育测评命题专家。

            # 通用硬约束
            1. 必须生成恰好 {{count}} 道题。

            # 题型规则
            {{questionTypeRules}}
            """;

    @BeforeEach
    void setUp() {
        loader = new QuestionSkillLoader(registry);
    }

    // ======================== loadBody 测试 ========================

    @Test
    @DisplayName("按题型编码加载正文，返回正确内容")
    void shouldLoadBodyForValidType() {
        ParsedQuestionSkill parsed = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        QuestionSkillMetadata metadata = QuestionSkillMetadata.from(parsed);

        when(registry.getMetadata("single_choice")).thenReturn(Optional.of(metadata));
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(parsed);

        String body = loader.loadBody("single_choice");

        assertNotNull(body);
        assertEquals(SINGLE_CHOICE_BODY, body);
        assertTrue(body.contains("单选题"));
        assertTrue(body.contains("{{optionCount}}"));
    }

    @Test
    @DisplayName("两次加载同一题型，正文只从 Registry 取一次（缓存命中）")
    void shouldCacheBodyOnSecondLoad() {
        ParsedQuestionSkill parsed = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        QuestionSkillMetadata metadata = QuestionSkillMetadata.from(parsed);

        when(registry.getMetadata("single_choice")).thenReturn(Optional.of(metadata));
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(parsed);

        // 第一次加载
        String first = loader.loadBody("single_choice");
        // 第二次加载
        String second = loader.loadBody("single_choice");

        assertEquals(first, second);
        // getParsedTypeSkill 只在 computeIfAbsent 内部调用一次
        verify(registry, times(1)).getParsedTypeSkill("single_choice");
        // getMetadata 被调用两次（缓存键在 computeIfAbsent 之前构建）
        verify(registry, times(2)).getMetadata("single_choice");
    }

    @Test
    @DisplayName("不同题型各自缓存，互不影响")
    void shouldCacheEachTypeSeparately() {
        ParsedQuestionSkill singleChoice = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        ParsedQuestionSkill code = createMockParsedSkill(
                "code", "code", CODE_BODY);

        QuestionSkillMetadata scMeta = QuestionSkillMetadata.from(singleChoice);
        QuestionSkillMetadata codeMeta = QuestionSkillMetadata.from(code);

        when(registry.getMetadata("single_choice")).thenReturn(Optional.of(scMeta));
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(singleChoice);
        when(registry.getMetadata("code")).thenReturn(Optional.of(codeMeta));
        when(registry.getParsedTypeSkill("code")).thenReturn(code);

        String scBody = loader.loadBody("single_choice");
        String codeBody = loader.loadBody("code");

        assertEquals(SINGLE_CHOICE_BODY, scBody);
        assertEquals(CODE_BODY, codeBody);

        verify(registry, times(1)).getParsedTypeSkill("single_choice");
        verify(registry, times(1)).getParsedTypeSkill("code");
    }

    @Test
    @DisplayName("加载未知题型时抛出 IllegalArgumentException")
    void shouldThrowForUnknownType() {
        when(registry.getMetadata("unknown")).thenReturn(Optional.empty());
        when(registry.allQuestionTypes()).thenReturn(Set.of("single_choice", "multiple_choice"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> loader.loadBody("unknown"));
        assertTrue(ex.getMessage().contains("未知题型"));
        assertTrue(ex.getMessage().contains("unknown"));
        assertTrue(ex.getMessage().contains("已注册的题型"));
    }

    @Test
    @DisplayName("题型已注册但 parsedTypeSkills 缺失时抛出 IllegalStateException")
    void shouldThrowWhenParsedSkillMissing() {
        ParsedQuestionSkill parsed = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        QuestionSkillMetadata metadata = QuestionSkillMetadata.from(parsed);

        when(registry.getMetadata("single_choice")).thenReturn(Optional.of(metadata));
        // getParsedTypeSkill 返回 null —— 异常情况
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadBody("single_choice"));
        assertTrue(ex.getMessage().contains("已注册但正文缺失"));
        assertTrue(ex.getMessage().contains("single_choice"));
    }

    // ======================== loadMetaBody 测试 ========================

    @Test
    @DisplayName("加载说明书正文返回正确内容")
    void shouldLoadMetaBody() {
        QuestionSkillManifest metaManifest = new QuestionSkillManifest();
        metaManifest.setName("question-generation");
        metaManifest.setDescription("Mock meta");
        metaManifest.setKeywords(List.of("出题"));
        metaManifest.setPriority(100);
        metaManifest.setAutoInvoke(true);

        ParsedQuestionSkill metaSkill = new ParsedQuestionSkill(
                "question-generation", metaManifest, META_BODY, "meta-hash");

        when(registry.getMetaSkill()).thenReturn(metaSkill);

        String metaBody = loader.loadMetaBody();

        assertNotNull(metaBody);
        assertEquals(META_BODY, metaBody);
        assertTrue(metaBody.contains("{{questionTypeRules}}"));
        assertTrue(metaBody.contains("{{count}}"));
    }

    @Test
    @DisplayName("说明书未加载时 loadMetaBody 抛出 IllegalStateException")
    void shouldThrowWhenMetaNotLoaded() {
        when(registry.getMetaSkill()).thenReturn(null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> loader.loadMetaBody());
        assertTrue(ex.getMessage().contains("说明书 Skill 未加载"));
    }

    // ======================== loadBodies 测试 ========================

    @Test
    @DisplayName("批量加载多个题型正文，返回有序映射")
    void shouldLoadBodiesInOrder() {
        ParsedQuestionSkill singleChoice = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        ParsedQuestionSkill code = createMockParsedSkill(
                "code", "code", CODE_BODY);

        when(registry.getMetadata("single_choice"))
                .thenReturn(Optional.of(QuestionSkillMetadata.from(singleChoice)));
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(singleChoice);
        when(registry.getMetadata("code"))
                .thenReturn(Optional.of(QuestionSkillMetadata.from(code)));
        when(registry.getParsedTypeSkill("code")).thenReturn(code);

        Map<String, String> bodies = loader.loadBodies(List.of("single_choice", "code"));

        assertEquals(2, bodies.size());
        assertEquals(SINGLE_CHOICE_BODY, bodies.get("single_choice"));
        assertEquals(CODE_BODY, bodies.get("code"));

        // 验证顺序：LinkedHashMap 保持插入顺序
        List<String> keys = List.copyOf(bodies.keySet());
        assertEquals("single_choice", keys.get(0));
        assertEquals("code", keys.get(1));
    }

    @Test
    @DisplayName("批量加载时空列表返回空映射")
    void shouldReturnEmptyMapForEmptyInput() {
        Map<String, String> bodies = loader.loadBodies(List.of());
        assertTrue(bodies.isEmpty());
    }

    // ======================== clearCache 测试 ========================

    @Test
    @DisplayName("清除缓存后再加载会重新从 Registry 取正文")
    void shouldReloadAfterClearCache() {
        ParsedQuestionSkill parsed = createMockParsedSkill(
                "single_choice", "single-choice", SINGLE_CHOICE_BODY);
        QuestionSkillMetadata metadata = QuestionSkillMetadata.from(parsed);

        when(registry.getMetadata("single_choice")).thenReturn(Optional.of(metadata));
        when(registry.getParsedTypeSkill("single_choice")).thenReturn(parsed);

        // 第一次加载
        loader.loadBody("single_choice");
        verify(registry, times(1)).getParsedTypeSkill("single_choice");

        // 清除缓存
        loader.clearCache();

        // 再次加载 —— 缓存未命中，重新调用 getParsedTypeSkill
        loader.loadBody("single_choice");
        verify(registry, times(2)).getParsedTypeSkill("single_choice");
    }

    // ======================== 辅助方法 ========================

    private static ParsedQuestionSkill createMockParsedSkill(
            String questionType, String skillKey, String body) {
        QuestionSkillManifest manifest = new QuestionSkillManifest();
        manifest.setName(skillKey);
        manifest.setQuestionType(questionType);
        manifest.setDescription("Mock description");
        manifest.setKeywords(List.of(questionType));
        manifest.setPriority(90);
        manifest.setAutoInvoke(false);
        manifest.setObjective(true);
        manifest.setDefaultScore(5);

        String hash = QuestionSkillParser.sha256(body);
        return new ParsedQuestionSkill(skillKey, manifest, body, hash);
    }
}
