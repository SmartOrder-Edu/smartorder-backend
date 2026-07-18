package com.smartorder.edu.student.skill;

import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 题目 Skill Prompt 组装器。
 *
 * <p>核心流程：
 * <ol>
 *   <li>加载说明书 Skill（通用约束 + 流程）</li>
 *   <li>按请求的题型编码加载对应题型 Skill 正文</li>
 *   <li>将 {@code {{questionTypeRules}}} 占位符替换为题型正文</li>
 *   <li>用 {@link PromptTemplate} 渲染剩余参数占位符</li>
 * </ol>
 *
 * <p>单次调用只生成一个题型批次的 Prompt，多个题型由调用方（Service 层）
 * 循环调用此 Assembler，每个题型独立组装。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionSkillPromptAssembler {

    private final QuestionSkillRegistry registry;
    private final QuestionSkillLoader loader;

    /**
     * 单题型 Prompt 组装。
     *
     * @param questionType        题型编码，如 "single_choice"
     * @param courseName          课程名称
     * @param topic               知识主题
     * @param count               题目数量
     * @param difficulty          难度等级 1-5
     * @param optionCount         选项数（选择题用）
     * @param programmingLanguage 编程语言（编程题用，可为 null）
     * @return 组装完成可直接发给 LLM 的 Prompt 字符串
     */
    public String assemble(String questionType,
                           String courseName,
                           String topic,
                           int count,
                           int difficulty,
                           int optionCount,
                           String programmingLanguage) {

        // 1. 加载说明书正文（通用约束 + {{questionTypeRules}} 占位符）
        String metaBody = loader.loadMetaBody();

        // 2. 加载题型 Skill 正文
        String typeBody = loader.loadBody(questionType);

        // 3. 将题型规则注入说明书的 {{questionTypeRules}} 占位符
        String mergedPrompt = metaBody.replace("{{questionTypeRules}}", typeBody);

        // 4. 渲染参数占位符
        return PromptTemplate.from(mergedPrompt)
                .apply(Map.of(
                        "courseName", courseName,
                        "topic", topic,
                        "difficulty", String.valueOf(difficulty),
                        "count", String.valueOf(count),
                        "language", "zh-CN",
                        "optionCount", String.valueOf(optionCount),
                        "programmingLanguage",
                                programmingLanguage != null ? programmingLanguage : "C",
                        "sqlDialect", "MySQL"
                ))
                .text();
    }

    /**
     * 批量预览：返回每个请求题型的 Skill 正文（不组装完整 Prompt，仅用于调试/校验）。
     *
     * @param questionTypes 题型编码列表
     * @return questionType → Skill 正文 的映射
     */
    public Map<String, String> previewBodies(Iterable<String> questionTypes) {
        return loader.loadBodies(questionTypes);
    }
}
