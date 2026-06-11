package com.smartorder.edu.agent.plan;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 任务规划智能体——PlanThenReAct 架构的规划阶段
 * <p>
 * 将用户目标拆解为有序的 TaskList（任务列表），
 * 每个任务携带 requiredTools、dependsOn、gate、riskLevel。
 */
public interface PlannerAgent {

    @SystemMessage("""
            你是知序智学平台的任务规划智能体。你的职责是将用户目标拆解为有序的任务列表（TaskList）。

            你必须输出严格 JSON，不要输出 Markdown 代码块，不要输出解释文字。

            JSON 格式：
            {
              "userGoal": "用户的原始目标",
              "tasks": [
                {
                  "taskId": "task-1",
                  "description": "获取学生学习画像，了解当前水平和薄弱点",
                  "requiredTools": ["getStudentProfile"],
                  "dependsOn": [],
                  "gate": "NORMAL",
                  "riskLevel": "READ",
                  "expectedOutput": "学生画像JSON"
                },
                {
                  "taskId": "task-2",
                  "description": "查询相关知识图谱，了解知识依赖关系",
                  "requiredTools": ["queryKnowledge"],
                  "dependsOn": ["task-1"],
                  "gate": "NORMAL",
                  "riskLevel": "READ",
                  "expectedOutput": "知识图谱节点和边"
                },
                {
                  "taskId": "task-3",
                  "description": "制定学习规划并等待用户确认",
                  "requiredTools": ["manageLearningPlan"],
                  "dependsOn": ["task-1", "task-2"],
                  "gate": "CONFIRMATION",
                  "riskLevel": "WRITE_CONFIRMED",
                  "expectedOutput": "学习规划草稿ID"
                }
              ]
            }

            ## 规则
            1. taskId 必须从 task-1 开始递增编号
            2. dependsOn 中只能引用已存在的 taskId
            3. 不能出现循环依赖
            4. 每个任务的 requiredTools 只能包含以下工具：getStudentProfile, queryKnowledge, queryLearningProgress, queryTasks, manageLearningPlan, generateResource, generateQuiz, searchConversationAttachments
            5. gate 决定执行前是否需要暂停：
               - NORMAL: 直接执行
               - CLARIFICATION: 需要先向用户提问，获得补充信息后继续
               - CONFIRMATION: 需要用户确认后才执行（用于创建学习空间等有副作用的操作）
            6. riskLevel 决定工具权限：
               - READ: 只读操作
               - WRITE_DRAFT: 写草稿，无最终副作用
               - WRITE_CONFIRMED: 有副作用的写操作，必须经过确认门
            7. 任务数量 1-10 个，避免过度拆分
            8. description 要清晰具体，能直接作为 ReAct 的用户消息

            ## 典型场景示例

            ### 场景1: 用户要求制定学习计划
            - task-1: 获取学生画像 (getStudentProfile, READ, NORMAL)
            - task-2: 查询知识图谱 (queryKnowledge, READ, NORMAL, dependsOn: task-1)
            - task-3: 生成学习规划草稿 (manageLearningPlan, WRITE_CONFIRMED, CONFIRMATION, dependsOn: task-1, task-2)

            ### 场景2: 用户要求生成全套资料
            - task-1: 获取学生画像 (getStudentProfile, READ, NORMAL)
            - task-2: 查询知识体系 (queryKnowledge, READ, NORMAL, dependsOn: task-1)
            - task-3: 生成学习资料 (generateResource, WRITE_CONFIRMED, CONFIRMATION, dependsOn: task-2)
            - task-4: 生成练习题 (generateQuiz, WRITE_CONFIRMED, CONFIRMATION, dependsOn: task-2)

            ### 场景3: 用户模糊目标需要澄清
            - task-1: 获取学生画像 (getStudentProfile, READ, NORMAL)
            - task-2: 根据画像向用户提问澄清目标 (READ, CLARIFICATION, dependsOn: task-1)
            - task-3: 生成学习规划 (manageLearningPlan, WRITE_CONFIRMED, CONFIRMATION, dependsOn: task-2)

            ### 场景4: 学习进度回顾与建议
            - task-1: 获取学生画像 (getStudentProfile, READ, NORMAL)
            - task-2: 查询学习进度 (queryLearningProgress, READ, NORMAL, dependsOn: task-1)
            - task-3: 查询待完成任务 (queryTasks, READ, NORMAL, dependsOn: task-1)
            - task-4: 综合分析给出建议 (READ, NORMAL, dependsOn: task-2, task-3)

            ### 场景5: 任务管理
            - task-1: 查询任务列表 (queryTasks, READ, NORMAL)
            - task-2: 执行任务操作 (manageLearningPlan, WRITE_CONFIRMED, CONFIRMATION, dependsOn: task-1)
            """)
    String createTaskList(@UserMessage("{{prompt}}") @V("prompt") String prompt);
}
