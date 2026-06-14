package com.smartorder.edu.agent.prompt;

import com.smartorder.edu.agent.tool.ToolHandler;
import dev.langchain4j.data.message.SystemMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 统一提示词加载器——从 classpath:prompts/ 目录加载所有 .md 提示词文件。
 * <p>
 * 启动时一次性预加载到内存缓存，运行时只读不写。
 * 模板类提示词使用 MessageFormat 的 {0} {1} 占位符，调用方通过 format() 方法替换。
 */
@Slf4j
@Service("agentPromptLoader")
public class PromptLoader {

    private final ResourceLoader resourceLoader;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private static final String BASE_PATH = "classpath:prompts/";

    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        loadAll();
        log.info("[PromptLoader] 提示词加载完成，共 {} 个文件", cache.size());
    }

    /**
     * 注入工具列表以替换提示词中的 {available_tools_list} 和 {available_tools_with_desc} 占位符。
     * 由 ToolRegistry 在完成所有工具注册后调用，避免 PromptLoader ↔ ToolRegistry 循环依赖。
     */
    public void injectToolList(Collection<ToolHandler> handlers) {
        String toolNames = handlers.stream()
                .map(ToolHandler::name).sorted().collect(Collectors.joining(", "));
        String toolsWithDesc = handlers.stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(h -> "   " + h.name() + "：" + h.description())
                .collect(Collectors.joining("\n"));

        for (Map.Entry<String, String> entry : cache.entrySet()) {
            String content = entry.getValue();
            if (content.contains("{available_tools_list}") || content.contains("{available_tools_with_desc}")) {
                content = content.replace("{available_tools_list}", toolNames);
                content = content.replace("{available_tools_with_desc}", toolsWithDesc);
                cache.put(entry.getKey(), content);
                log.info("[PromptLoader] 动态注入工具列表到: {} ({} 个工具)", entry.getKey(), handlers.size());
            }
        }
    }

    // ============ Public: 静态 System Prompt ============

    public SystemMessage getMainAgentSystemMessage() {
        return SystemMessage.from(cache.get("system/main-agent.md"));
    }

    public String getMainAgentSystemPrompt() {
        return cache.get("system/main-agent.md");
    }

    public String getIntentClassifierPrompt() {
        return cache.get("system/intent-classifier.md");
    }

    public String getStepVerifierPrompt() {
        return cache.get("system/step-verifier.md");
    }

    public SystemMessage getPlannerCreateSystemMessage() {
        return SystemMessage.from(cache.get("system/planner-create.md"));
    }

    public String getPlannerCreatePrompt() {
        return cache.get("system/planner-create.md");
    }

    public SystemMessage getPlannerReplanSystemMessage() {
        return SystemMessage.from(cache.get("system/planner-replan.md"));
    }

    public String getPlannerReplanPrompt() {
        return cache.get("system/planner-replan.md");
    }

    public SystemMessage getSummarizerSystemMessage() {
        return SystemMessage.from(cache.get("system/summarizer.md"));
    }

    public String getSummarizerPrompt() {
        return cache.get("system/summarizer.md");
    }

    // ============ Public: 模板 Prompt（调用方自行 format） ============

    /** @return 模板字符串，占位符 {0}=资源类型名称, {1}=知识点 */
    public String getResourceGenTemplate() {
        return cache.get("templates/resource-generation.md");
    }

    /** @return 模板字符串，占位符 {0}=知识点, {1}=数量, {2}=题型, {3}=难度 */
    public String getQuizGenTemplate() {
        return cache.get("templates/quiz-generation.md");
    }

    /** @return 模板字符串，占位符 {0}=用户目标, {1}=画像JSON, {2}=意图提示, {3}=附件摘要 */
    public String getPlannerInputTemplate() {
        return cache.get("templates/planner-input.md");
    }

    /** @return 模板字符串，占位符 {0}=stepId, {1}=描述, {2}=预期产出, {3}=前置任务结果 */
    public String getTaskSystemMsgTemplate() {
        return cache.get("templates/task-system-message.md");
    }

    /** @return 模板字符串，占位符 {0}=用户目标, {1}=已完成任务, {2}=失败任务, {3}=剩余任务 */
    public String getReplanInputTemplate() {
        return cache.get("templates/replan-input.md");
    }

    /** @return 模板字符串，占位符 {0}=stepId, {1}=描述, {2}=预期产出, {3}=所需工具, {4}=结果, {5}=前置摘要 */
    public String getVerificationRequestTemplate() {
        return cache.get("templates/verification-request.md");
    }

    // ============ Public: 澄清/指令文本 ============

    public String getDefaultClarification() {
        return cache.get("clarification/default.md");
    }

    public String getPendingPlanDraftClarification() {
        return cache.get("clarification/pending-plan-draft.md");
    }

    public String getGoalDescriptionClarification() {
        return cache.get("clarification/goal-description.md");
    }

    public String getResourceTopicClarification() {
        return cache.get("clarification/resource-topic.md");
    }

    public String getCitationInstruction() {
        return cache.get("clarification/citation-instruction.md");
    }

    public String getMaxRoundsFallback() {
        return cache.get("clarification/max-rounds-fallback.md");
    }

    // ============ Public: 工具方法 ============

    /**
     * 使用 MessageFormat 替换模板中的 {0} {1} ... 占位符。
     */
    public static String format(String template, Object... args) {
        return MessageFormat.format(template, args);
    }

    // ============ Private ============

    private void loadAll() {
        load("system/main-agent.md");
        load("system/intent-classifier.md");
        load("system/step-verifier.md");
        load("system/planner-create.md");
        load("system/planner-replan.md");
        load("system/summarizer.md");
        load("templates/resource-generation.md");
        load("templates/quiz-generation.md");
        load("templates/planner-input.md");
        load("templates/task-system-message.md");
        load("templates/replan-input.md");
        load("templates/verification-request.md");
        load("clarification/default.md");
        load("clarification/pending-plan-draft.md");
        load("clarification/goal-description.md");
        load("clarification/resource-topic.md");
        load("clarification/profile-clarification.md");
        load("clarification/citation-instruction.md");
        load("clarification/max-rounds-fallback.md");
    }

    private void load(String path) {
        try {
            Resource resource = resourceLoader.getResource(BASE_PATH + path);
            if (!resource.exists()) {
                log.error("[PromptLoader] 资源文件不存在: {}", path);
                return;
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // 统一换行符，去除末尾多余空行
            content = content.replace("\r\n", "\n").replace("\r", "\n").stripTrailing();
            cache.put(path, content);
            log.debug("[PromptLoader] 加载提示词: {} ({} chars)", path, content.length());
        } catch (IOException e) {
            log.error("[PromptLoader] 读取提示词失败: {}", path, e);
        }
    }
}
