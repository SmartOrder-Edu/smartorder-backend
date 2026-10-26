package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.service.impl.SelfManagedReActEngine;
import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一澄清工具 —— 让 LLM 主动调用此工具向用户提问，而非在回复文本中提问。
 * <p>
 * 设计原则：工具本身不执行澄清逻辑（不写 DB、不发 SSE），仅定义参数 Schema。
 * 实际的暂停逻辑由 {@code SelfManagedReActEngine} 在检测到 askUser 工具调用时拦截处理。
 *
 * @author 谢光湘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AskUserTool implements ToolHandler {

    @Override
    public String name() {
        return "askUser";
    }

    @Override
    public String description() {
        return "当你需要向用户提问以澄清需求、获取更多信息或确认细节时使用此工具。" +
                "你应该提供清晰的问题、选择答案类型（单选/多选/文本/数字），并在适用时提供选项列表。" +
                "不要在你的文本回复中直接向用户提问——始终使用此工具。一次只问一个问题。" +
                "参数说明：question（问题文本）、answerType（SINGLE_SELECT/MULTI_SELECT/TEXT/NUMBER）、" +
                "options（仅单选/多选时需要，每项包含label和value）、slotKey（语义标识，如target_scope、current_level）";
    }

    @Override
    public Class<?> requestType() {
        return AskUserRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROFILE;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        // 不做实际业务逻辑。
        // SelfManagedReActEngine 在工具执行前检测到 askUser 名称时直接拦截，
        // 将参数写入 AgentRuntimeContext.clarificationPayload 并退出 ReAct 循环。
        // 如果因为某些原因走到了这里，返回一个成功标记。
        AskUserRequest req = (AskUserRequest) request;
        log.info("[AskUser] 工具被调用（应由 ReAct 引擎拦截，此路径为兜底）: slotKey={}, answerType={}",
                req.getSlotKey(), req.getAnswerType());
        return ToolResult.success("ok");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AskUserRequest {
        /** 向用户提出的问题（一句话） */
        private String question;

        /** 答案类型：SINGLE_SELECT | MULTI_SELECT | TEXT | NUMBER */
        private String answerType;

        /** 选项列表（仅 SINGLE_SELECT / MULTI_SELECT 需要） */
        private List<OptionItem> options;

        /** 槽位标识 (snake_case, 如 target_scope, current_level) */
        private String slotKey;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class OptionItem {
            private String label;
            private String value;
        }
    }
}
