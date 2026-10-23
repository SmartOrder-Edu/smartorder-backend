package com.smartorder.edu.student.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QuestionSkillParser} 单元测试。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
class QuestionSkillParserTest {

    private final QuestionSkillParser parser = new QuestionSkillParser();

    /**
     * 合法的单题型 SKILL.md 内容（snake_case YAML，与真实文件格式一致）。
     */
    private static final String VALID_TYPE_SKILL = """
            ---
            name: single-choice
            description: 生成单选题，固定选项数量，包含结构化选项解析
            when_to_use: 当请求题型编码为 single_choice 或需要生成单选题时加载
            keywords:
              - 单选题
              - single_choice
              - 选择题
              - 单选
            priority: 90
            auto_invoke: false
            question_type: single_choice
            objective: true
            default_score: 5
            default_option_count: 4
            ---

            生成单选题，type 固定为 "单选题"。

            # 题目约束
            1. 每题必须有 {{optionCount}} 个选项。
            2. options 为字符串数组，按顺序对应 A、B、C、D……
            3. answer 为单个大写字母字符串。
            """;

    /**
     * 合法的说明书（meta Skill）YAML。
     */
    private static final String VALID_META_SKILL = """
            ---
            name: question-generation
            description: 根据题型按需加载对应的题目生成 Skill
            when_to_use: 需要生成测评题目时
            keywords:
              - 出题
              - 生成题目
              - 测评
            priority: 100
            auto_invoke: true
            allowed_tools:
              - profile.retrieve
              - question.generate
            question_type_mapping:
              single_choice: single-choice
              multiple_choice: multiple-choice
            ---

            # 角色
            你是专业教育测评命题专家。

            # 通用硬约束
            1. 只围绕课程和知识主题命题，不偏题。
            2. 必须生成恰好 {{count}} 道题。
            """;

    @Test
    @DisplayName("正常解析题型 SKILL.md，所有字段正确映射")
    void shouldParseValidTypeSkill() {
        ParsedQuestionSkill result = parser.parse("single-choice", VALID_TYPE_SKILL);

        assertEquals("single-choice", result.skillKey());
        assertNotNull(result.manifest());
        assertEquals("single-choice", result.manifest().getName());
        assertEquals("single_choice", result.manifest().getQuestionType());
        assertTrue(result.manifest().getObjective());
        assertEquals(5, result.manifest().getDefaultScore().intValue());
        assertEquals(4, result.manifest().getDefaultOptionCount().intValue());
        assertEquals(90, result.manifest().getPriority());
        assertFalse(result.manifest().isAutoInvoke());
        assertEquals("生成单选题，固定选项数量，包含结构化选项解析",
                result.manifest().getDescription());
        assertEquals(4, result.manifest().getKeywords().size());
        assertTrue(result.manifest().getKeywords().contains("单选题"));

        // 正文不包含 YAML frontmatter
        assertNotNull(result.body());
        assertFalse(result.body().isEmpty());
        assertTrue(result.body().contains("生成单选题"));
        assertTrue(result.body().contains("# 题目约束"));
        assertFalse(result.body().contains("---")); // 不应残留 YAML 标记

        // SHA-256 应为 64 字符的十六进制串
        assertNotNull(result.contentHash());
        assertEquals(64, result.contentHash().length());
        assertTrue(result.contentHash().matches("^[0-9a-f]{64}$"));

        assertFalse(result.isMeta());
        assertTrue(result.isQuestionType());
    }

    @Test
    @DisplayName("正常解析说明书 SKILL.md，isMeta() 基于 YAML name 字段判断")
    void shouldParseValidMetaSkill() {
        // 说明书目录名为 "questions"（skills/questions/SKILL.md 的父目录），
        // 但 YAML name 为 "question-generation" —— isMeta() 应以 YAML 为准
        ParsedQuestionSkill result = parser.parse("questions", VALID_META_SKILL);

        assertEquals("questions", result.skillKey());
        // isMeta() 基于 manifest.name == "question-generation"，而非目录名
        assertTrue(result.isMeta());
        assertFalse(result.isQuestionType());

        assertNotNull(result.manifest());
        assertEquals("question-generation", result.manifest().getName());
        assertTrue(result.manifest().isAutoInvoke());
        assertEquals(100, result.manifest().getPriority());
        assertNotNull(result.manifest().getAllowedTools());
        assertEquals(2, result.manifest().getAllowedTools().size());
        assertNotNull(result.manifest().getQuestionTypeMapping());
        assertEquals(2, result.manifest().getQuestionTypeMapping().size());
        assertEquals("single-choice", result.manifest().getQuestionTypeMapping().get("single_choice"));

        // 说明书没有题型专属字段
        assertNull(result.manifest().getQuestionType());
        assertNull(result.manifest().getObjective());
        assertNull(result.manifest().getDefaultScore());
    }

    @Test
    @DisplayName("说明书目录名为 question-generation 时 isMeta() 返回 true")
    void shouldIdentifyMetaWhenDirectoryNameMatches() {
        ParsedQuestionSkill result = parser.parse("question-generation", VALID_META_SKILL);

        assertEquals("question-generation", result.skillKey());
        assertTrue(result.isMeta());
        assertFalse(result.isQuestionType());
    }

    @Test
    @DisplayName("缺少 YAML frontmatter 开头 --- 时抛出 IllegalArgumentException")
    void shouldThrowWhenMissingFrontmatter() {
        String content = "# Just a markdown file\n\nNo frontmatter here.";

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("test", content));
        assertTrue(ex.getMessage().contains("YAML frontmatter (---) 开头"));
        assertTrue(ex.getMessage().contains("test"));
    }

    @Test
    @DisplayName("null 内容抛出异常")
    void shouldThrowWhenContentIsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("test", null));
        assertTrue(ex.getMessage().contains("YAML frontmatter (---) 开头"));
    }

    @Test
    @DisplayName("只有开头 --- 但缺少结束标记时抛出 IllegalArgumentException")
    void shouldThrowWhenMissingClosingFrontmatter() {
        String content = """
                ---
                name: test
                description: missing closing marker

                Body without closing frontmatter.
                """;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("test", content));
        assertTrue(ex.getMessage().contains("缺少 YAML frontmatter 结束标记 ---"));
    }

    @Test
    @DisplayName("YAML 内容格式错误时抛出 IllegalArgumentException")
    void shouldThrowWhenYamlIsMalformed() {
        // YAML 缩进错误：[a, b 是非法的流序列
        String content = """
                ---
                name: test
                keywords: [a, b
                priority: "not_a_number"
                ---

                Body text.
                """;

        // priority 是 int 类型，YAML 字符串 "not_a_number" 也会导致反序列化失败
        // 但 keywords 的错误更早触发
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("test", content));
        assertTrue(ex.getMessage().contains("YAML 解析失败"));
        assertTrue(ex.getMessage().contains("test"));
    }

    @Test
    @DisplayName("YAML 后无正文时 body 为空字符串")
    void shouldReturnEmptyBodyWhenNoContentAfterFrontmatter() {
        String content = """
                ---
                name: test
                description: just frontmatter
                when_to_use: never
                keywords:
                  - test
                priority: 50
                auto_invoke: false
                question_type: test_type
                objective: true
                default_score: 10
                ---
                """;

        ParsedQuestionSkill result = parser.parse("test", content);

        assertNotNull(result.body());
        assertTrue(result.body().isEmpty());
        assertNotNull(result.manifest());
        assertEquals("test", result.manifest().getName());
    }

    @Test
    @DisplayName("相同内容两次解析产生相同的 SHA-256")
    void shouldProduceDeterministicSha256() {
        ParsedQuestionSkill first = parser.parse("single-choice", VALID_TYPE_SKILL);
        ParsedQuestionSkill second = parser.parse("single-choice", VALID_TYPE_SKILL);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(64, first.contentHash().length());
        // 不同内容应该产生不同的 hash
        assertNotEquals(first.contentHash(),
                parser.parse("different", VALID_META_SKILL).contentHash());
    }

    @Test
    @DisplayName("使用 CRLF 换行符的文件也能正常解析")
    void shouldParseCrlfLineEndings() {
        String crlfContent = "---\r\n" +
                "name: single-choice\r\n" +
                "description: 测试描述\r\n" +
                "when_to_use: 用于测试\r\n" +
                "keywords:\r\n" +
                "  - 测试\r\n" +
                "  - crlf\r\n" +
                "priority: 80\r\n" +
                "auto_invoke: false\r\n" +
                "question_type: single_choice\r\n" +
                "objective: true\r\n" +
                "default_score: 5\r\n" +
                "---\r\n" +
                "\r\n" +
                "这是 CRLF 换行符的正文内容。\r\n";

        ParsedQuestionSkill result = parser.parse("single-choice", crlfContent);

        assertEquals("single-choice", result.skillKey());
        assertEquals("single_choice", result.manifest().getQuestionType());
        assertEquals(5, result.manifest().getDefaultScore().intValue());
        assertTrue(result.body().contains("CRLF 换行符"));
    }

    @Test
    @DisplayName("规范化目录名：空格转为横线，大写转小写")
    void shouldNormalizeDirectoryName() {
        ParsedQuestionSkill result = parser.parse("Single Choice", VALID_TYPE_SKILL);

        assertEquals("single-choice", result.skillKey());
    }

    @Test
    @DisplayName("题型 Skill 的 YAML name 不是 question-generation 时 isMeta() 返回 false")
    void shouldReturnIsMetaFalseForTypeSkills() {
        // 题型 Skill 的 YAML name 是 "single-choice"，不是 "question-generation"
        ParsedQuestionSkill result = parser.parse("single-choice", VALID_TYPE_SKILL);

        assertEquals("single-choice", result.skillKey());
        assertFalse(result.isMeta());
        assertTrue(result.isQuestionType());
    }
}
