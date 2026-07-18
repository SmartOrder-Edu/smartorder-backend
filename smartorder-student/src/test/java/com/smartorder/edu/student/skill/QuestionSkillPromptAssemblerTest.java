package com.smartorder.edu.student.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link QuestionSkillPromptAssembler} 单元测试。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@ExtendWith(MockitoExtension.class)
class QuestionSkillPromptAssemblerTest {

    @Mock
    private QuestionSkillRegistry registry;

    @Mock
    private QuestionSkillLoader loader;

    private QuestionSkillPromptAssembler assembler;

    private static final String META_BODY = """
            # 角色
            你是专业教育测评命题专家。

            # 输入
            - 课程名称：{{courseName}}
            - 知识主题：{{topic}}
            - 题目数量：{{count}}
            - 难度等级：{{difficulty}}

            # 通用硬约束
            1. 必须生成恰好 {{count}} 道题。
            2. 每道题 difficulty 必须等于 {{difficulty}}。

            # 最终输出
            只输出合法 JSON。

            # 题型规则
            {{questionTypeRules}}
            """;

    private static final String SINGLE_CHOICE_BODY = """
            生成单选题，type 固定为 "单选题"。

            # 题目约束
            1. 每题必须有 {{optionCount}} 个选项。
            2. answer 为单个大写字母字符串。
            3. score 固定为 5。
            """;

    private static final String CODE_BODY = """
            生成编程题，type 固定为 "编程题"，编程语言使用 {{programmingLanguage}}。

            约束：
            1. answer 格式：完整的参考答案代码字符串。
            2. score 默认 15。
            """;

    private static final String SQL_BODY = """
            生成 SQL 题，type 固定为 "SQL题"，SQL 方言使用 {{sqlDialect}}。

            约束：
            1. answer 格式：完整的可执行 SQL 语句字符串。
            2. score 默认 15。
            """;

    @BeforeEach
    void setUp() {
        assembler = new QuestionSkillPromptAssembler(registry, loader);
    }

    // ======================== 正常组装测试 ========================

    @Test
    @DisplayName("正常组装单选题 Prompt，包含说明书和题型规则")
    void shouldAssemblePromptForSingleChoice() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("single_choice")).thenReturn(SINGLE_CHOICE_BODY);

        String prompt = assembler.assemble(
                "single_choice", "数据结构", "栈", 5, 3, 4, null);

        assertNotNull(prompt);
        // 应包含说明书内容
        assertTrue(prompt.contains("专业教育测评命题专家"));
        // 应包含题型规则
        assertTrue(prompt.contains("单选题"));
        assertTrue(prompt.contains("固定为"));
        // 不应残留占位符
        assertFalse(prompt.contains("{{questionTypeRules}}"));
        // 参数应被替换
        assertTrue(prompt.contains("数据结构"));
        assertTrue(prompt.contains("栈"));
        assertTrue(prompt.contains("5"));
        assertTrue(prompt.contains("3"));
    }

    @Test
    @DisplayName("组装后的 Prompt 不包含未替换的 Mustache 占位符")
    void shouldReplaceAllPlaceholders() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("single_choice")).thenReturn(SINGLE_CHOICE_BODY);

        String prompt = assembler.assemble(
                "single_choice", "操作系统", "进程调度", 10, 4, 4, null);

        // 所有 {{...}} 占位符都应被替换
        assertFalse(prompt.contains("{{courseName}}"));
        assertFalse(prompt.contains("{{topic}}"));
        assertFalse(prompt.contains("{{difficulty}}"));
        assertFalse(prompt.contains("{{count}}"));
        assertFalse(prompt.contains("{{language}}"));
        assertFalse(prompt.contains("{{optionCount}}"));
        assertFalse(prompt.contains("{{questionTypeRules}}"));
    }

    @Test
    @DisplayName("单选题题型规则中的 {{optionCount}} 被替换为具体数值")
    void shouldReplaceOptionCountInTypeBody() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("single_choice")).thenReturn(SINGLE_CHOICE_BODY);

        String prompt = assembler.assemble(
                "single_choice", "数据结构", "二叉树", 3, 2, 6, null);

        // optionCount=6 应出现在提示词中
        assertTrue(prompt.contains("6"));
        assertFalse(prompt.contains("{{optionCount}}"));
    }

    // ======================== 各题型测试 ========================

    @Test
    @DisplayName("编程题：{{programmingLanguage}} 替换为具体语言")
    void shouldReplaceProgrammingLanguageForCodeType() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("code")).thenReturn(CODE_BODY);

        String prompt = assembler.assemble(
                "code", "数据结构", "链表", 2, 4, 0, "Java");

        assertTrue(prompt.contains("编程题"));
        assertTrue(prompt.contains("Java"));
        assertFalse(prompt.contains("{{programmingLanguage}}"));
    }

    @Test
    @DisplayName("编程题 programmingLanguage 为 null 时默认使用 C")
    void shouldDefaultProgrammingLanguageToC() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("code")).thenReturn(CODE_BODY);

        String prompt = assembler.assemble(
                "code", "数据结构", "链表", 2, 4, 0, null);

        // 默认编程语言为 C
        assertFalse(prompt.contains("{{programmingLanguage}}"));
        assertTrue(prompt.contains("C"));
    }

    @Test
    @DisplayName("SQL 题：{{sqlDialect}} 替换为 MySQL")
    void shouldSetSqlDialectToMysql() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("sql")).thenReturn(SQL_BODY);

        String prompt = assembler.assemble(
                "sql", "数据库", "JOIN 查询", 3, 3, 0, null);

        assertTrue(prompt.contains("SQL题"));
        assertTrue(prompt.contains("MySQL"));
        assertFalse(prompt.contains("{{sqlDialect}}"));
    }

    @Test
    @DisplayName("所有 9 种题型都能正常加载和组装")
    void shouldAssembleAllNineTypes() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        // 为每种题型生成简单的正文 Mock
        String[] types = {
                "single_choice", "multiple_choice", "fill_blank",
                "judge", "code", "sql", "scenario",
                "short_answer", "multimodal"
        };
        for (String type : types) {
            when(loader.loadBody(type)).thenReturn(
                    "生成题目，type 为 \"" + type + "\"。\n约束：options 为 []。");
        }

        for (String type : types) {
            String prompt = assembler.assemble(
                    type, "测试课程", "测试主题", 1, 3, 0, null);

            assertNotNull(prompt, type + " 的 Prompt 不应为 null");
            assertFalse(prompt.contains("{{questionTypeRules}}"),
                    type + " 的 Prompt 不应含未替换的题型占位符");
            assertTrue(prompt.contains("专业教育测评命题专家"),
                    type + " 的 Prompt 应包含说明书角色定义");
        }

        // 验证所有 9 个题型都被加载了
        for (String type : types) {
            verify(loader).loadBody(type);
        }
    }

    @Test
    @DisplayName("未知题型时 assemble 抛出 IllegalArgumentException")
    void shouldThrowForUnknownType() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("unknown"))
                .thenThrow(new IllegalArgumentException("未知题型: unknown"));

        assertThrows(IllegalArgumentException.class, () ->
                assembler.assemble("unknown", "课程", "主题", 3, 3, 4, null));
    }

    // ======================== 参数渲染测试 ========================

    @Test
    @DisplayName("difficulty 参数被正确渲染到 Prompt")
    void shouldRenderDifficultyParameter() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("single_choice")).thenReturn("单选题规则");

        String promptEasy = assembler.assemble(
                "single_choice", "课程", "主题", 3, 1, 4, null);
        String promptHard = assembler.assemble(
                "single_choice", "课程", "主题", 3, 5, 4, null);

        // 两版 Prompt 中 difficulty 值不同
        assertTrue(promptEasy.contains("1") || promptEasy.contains("难度等级：1"));
        assertTrue(promptHard.contains("5") || promptHard.contains("难度等级：5"));
    }

    @Test
    @DisplayName("count 参数被正确渲染到 Prompt")
    void shouldRenderCountParameter() {
        when(loader.loadMetaBody()).thenReturn(META_BODY);
        when(loader.loadBody("single_choice")).thenReturn("单选题规则");

        String prompt10 = assembler.assemble(
                "single_choice", "课程", "主题", 10, 3, 4, null);
        String prompt3 = assembler.assemble(
                "single_choice", "课程", "主题", 3, 3, 4, null);

        // count=10 和 count=3 都应渲染
        assertTrue(prompt10.contains("10"));
        assertTrue(prompt3.contains("3"));
    }

    // ======================== previewBodies 测试 ========================

    @Test
    @DisplayName("previewBodies 返回题型到正文的有序映射")
    void shouldPreviewBodiesCorrectly() {
        when(loader.loadBodies(List.of("single_choice", "code")))
                .thenReturn(Map.of("single_choice", SINGLE_CHOICE_BODY, "code", CODE_BODY));

        Map<String, String> preview = assembler.previewBodies(List.of("single_choice", "code"));

        assertEquals(2, preview.size());
        assertEquals(SINGLE_CHOICE_BODY, preview.get("single_choice"));
        assertEquals(CODE_BODY, preview.get("code"));
    }
}
