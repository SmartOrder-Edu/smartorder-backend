package com.smartorder.edu.student.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 题目 Skill 正文按需加载器。
 *
 * <p>缓存键格式：{@code questionType:contentHash}
 * 例如：{@code single_choice:a1b2c3d4e5f6...}
 *
 * <p>即使 Skill 文件更新后重新部署（contentHash 变化），缓存键自动错开，
 * 无需手动失效。由于当前是 classpath 文件场景，更新 = 重启，缓存自然重建。
 * 后续若迁移到数据库存储并支持热更新，此机制可直接复用。
 *
 * @author Ma Qiang
 * @since 2026-07-17
 */
@Slf4j
@Component
public class QuestionSkillLoader {

    private final QuestionSkillRegistry registry;

    /** questionType → 正文缓存 */
    private final Map<String, String> bodyCache = new ConcurrentHashMap<>();

    public QuestionSkillLoader(QuestionSkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按题型编码加载 Skill 正文。
     *
     * @param questionType 题型编码，如 "single_choice"
     * @return Skill 正文（Markdown Prompt 规则文本）
     * @throws IllegalArgumentException 如果题型未注册
     */
    public String loadBody(String questionType) {
        QuestionSkillMetadata metadata = registry.getMetadata(questionType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知题型: " + questionType
                                + "，已注册的题型: " + registry.allQuestionTypes()));

        String cacheKey = questionType + ":" + metadata.contentHash();

        return bodyCache.computeIfAbsent(cacheKey, key -> {
            ParsedQuestionSkill parsed = registry.getParsedTypeSkill(questionType);
            if (parsed == null) {
                throw new IllegalStateException(
                        "题型 Skill 已注册但正文缺失: " + questionType);
            }
            log.debug("[QuestionSkillLoader] 加载 Skill 正文: type={}, bodyLength={}",
                    questionType, parsed.body().length());
            return parsed.body();
        });
    }

    /**
     * 加载说明书 Skill 正文（通用约束 + 流程说明）。
     *
     * @return 说明书正文
     * @throws IllegalStateException 如果说明书未加载
     */
    public String loadMetaBody() {
        ParsedQuestionSkill meta = registry.getMetaSkill();
        if (meta == null) {
            throw new IllegalStateException("说明书 Skill 未加载");
        }
        return meta.body();
    }

    /**
     * 批量加载多个题型的正文。
     *
     * @param questionTypes 题型编码列表
     * @return questionType → body 的有序映射
     */
    public Map<String, String> loadBodies(Iterable<String> questionTypes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String type : questionTypes) {
            result.put(type, loadBody(type));
        }
        return result;
    }

    /** 清除所有正文缓存（仅测试/调试用）。 */
    public void clearCache() {
        bodyCache.clear();
        log.info("[QuestionSkillLoader] 正文缓存已清除");
    }
}
