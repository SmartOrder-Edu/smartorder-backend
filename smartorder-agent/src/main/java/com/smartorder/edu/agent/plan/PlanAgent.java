package com.smartorder.edu.agent.plan;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 学习规划智能体——使用 AiServices 生成结构化规划 JSON
 */
public interface PlanAgent {

    @SystemMessage("""
            你是知序智学平台的学习规划智能体。

            你需要根据用户目标、学生画像、学习进度，生成结构化的学习规划。

            必须输出严格 JSON，不要输出 Markdown，不要输出解释文字。

            JSON 格式：
            {
              "spaceName": "学习空间名称",
              "type": "course|exam|project|career|competition",
              "description": "简要描述",
              "goals": ["目标1", "目标2"],
              "startDate": "yyyy-MM-dd",
              "targetEndDate": "yyyy-MM-dd",
              "phases": [
                {
                  "phaseIndex": 1,
                  "phaseName": "阶段名称",
                  "weeks": 2,
                  "knowledgePoints": ["知识点1", "知识点2"],
                  "resources": [
                    {"resourceType": "PPT", "title": "资源标题"},
                    {"resourceType": "QUIZ", "title": "练习题标题"}
                  ]
                }
              ],
              "dailyPlan": {
                "morning": "早间学习安排",
                "evening": "晚间学习安排"
              },
              "knowledgeGraph": {
                "nodes": [
                  {"name": "知识点名称", "category": "concept|skill|topic", "importance": 3}
                ],
                "edges": [
                  {"from": "源知识点", "to": "目标知识点", "type": "prerequisite_of"}
                ]
              }
            }

            要求：
            1. goals 必须至少包含 1 个学习目标
            2. 学习阶段 3-5 个，每个 1-3 周
            3. 每个阶段至少 2 个知识点
            4. 知识点之间设置合理的依赖关系
            5. 任务具体、可执行
            """)
    String createPlan(@UserMessage String prompt);
}
