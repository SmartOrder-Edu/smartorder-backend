package com.smartorder.edu.agent.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePromptContractTest {

    @Test
    void routerMustRequireAllIndependentProfileFactsToBeSelected() throws IOException {
        String prompt = readPrompt("prompts/profile/dimension-router.md");

        assertTrue(prompt.contains("逐条扫描当前用户消息中的全部独立事实"));
        assertTrue(prompt.contains("不得因为已经选中一个维度而忽略同一消息中的其他可写入事实"));
        assertTrue(prompt.contains("仅表达想/准备学习某技术栈或课程时，选择 `learning_goals`"));
        assertTrue(prompt.contains("只有用户明确表示正在学习、已经开始投入或已经学过时，才选择 `learning_background`"));
        assertTrue(prompt.contains("\"dimensionKey\": \"learning_goals\""));
        assertTrue(prompt.contains("\"evidence\": \"技术栈打算学习java\""));
    }

    @Test
    void extractorMustCoverEverySelectedDimensionWithExplicitEvidence() throws IOException {
        String prompt = readPrompt("prompts/profile/extraction-shell.md");

        assertTrue(prompt.contains("对已选维度逐一覆盖"));
        assertTrue(prompt.contains("每个已选维度只要存在当前消息中的明确证据，就必须生成对应的 `dimensionUpdate`"));
        assertTrue(prompt.contains("不得只输出最显眼的一个维度"));
        assertTrue(prompt.contains("不得把“想学/准备学”推断为已经学习、BEGINNER、experience_months 或 IN_PROGRESS"));
        assertTrue(prompt.contains("相对期限仅保留在 description 中，不得填写 deadline"));
        assertTrue(prompt.contains("evidence 必须覆盖该 dimensionUpdate 中的全部用户事实"));
        assertTrue(prompt.contains("不得使用固定的 -001 后缀"));
    }

    private String readPrompt(String path) throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Prompt resource not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
