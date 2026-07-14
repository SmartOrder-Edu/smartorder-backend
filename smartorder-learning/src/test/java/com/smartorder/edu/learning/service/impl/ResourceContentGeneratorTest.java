package com.smartorder.edu.learning.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.ResourceGenerationContext;
import com.smartorder.edu.learning.service.ResourceGenerationContextBuilder;
import com.smartorder.edu.learning.service.recommendation.BilibiliVideoRecommendationService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceContentGeneratorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BilibiliVideoRecommendationService bilibiliVideoRecommendationService;
    private ObjectProvider<ChatModel> chatModelProvider;
    private ResourceGenerationContextBuilder contextBuilder;
    private ChatModel chatModel;
    private ResourceContentGenerator generator;

    @BeforeEach
    void setUp() {
        bilibiliVideoRecommendationService = mock(BilibiliVideoRecommendationService.class);
        chatModelProvider = mock(ObjectProvider.class);
        contextBuilder = mock(ResourceGenerationContextBuilder.class);
        chatModel = mock(ChatModel.class);
        generator = new ResourceContentGenerator(bilibiliVideoRecommendationService, chatModelProvider, contextBuilder);
    }

    @Test
    void generateDocUsesLlmContentWhenMarkdownPassesValidation() {
        Resource resource = docResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                # 进程调度算法

                ## 学习目标
                能解释进程调度算法的选择依据，并能比较不同算法的适用场景。

                ## 核心概念
                进程调度算法决定 CPU 在多个就绪进程之间如何分配执行机会。学习时要先区分三个量：到达时间表示进程进入就绪队列的时间，运行时间表示进程需要占用 CPU 的总时长，等待时间表示进程在就绪队列中尚未执行的累计时间。不同算法其实是在公平性、响应速度和吞吐量之间做取舍。

                ## 例题讲解
                例题：给定 P1、P2、P3 的到达时间和运行时间，使用先来先服务算法时，先按到达时间排序，再依次计算等待时间。

                ## 易错点
                不要把到达时间和运行时间混为一谈。

                ## 复盘问题
                - 进程调度算法什么时候更关注响应时间？
                - 为什么短作业优先可能导致饥饿？
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("markdown");
        assertThat(content.getContent()).contains("## 核心概念", "例题：给定 P1");
        assertThat(content.getReviewResult()).contains("\"source\":\"llm\"", "\"status\":\"passed\"");
    }

    @Test
    void generateDocFallsBackToTemplateWhenLlmMarkdownFailsValidation() {
        Resource resource = docResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("太简单了，自己看书。");

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContent()).contains("## Agent 生成理由", "## 学习目标");
        assertThat(content.getContent()).doesNotContain("太简单了");
        assertThat(content.getReviewResult()).contains("\"source\":\"template_fallback\"", "\"status\":\"needs_review\"");
    }

    @Test
    void generateHtmlUsesLlmFragmentWhenValidationPasses() {
        Resource resource = htmlResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                <style>
                  .generated-resource-html .stack-layout { display: grid; gap: 16px; }
                  .generated-resource-html .concept-table { width: 100%; border-collapse: collapse; }
                </style>
                <div class="stack-layout" style="padding: 16px; border-radius: 12px;">
                  <div class="hero-block">
                    <h1>交互知识页：进程调度算法</h1>
                    <p class="note">围绕进程调度算法建立可操作的判断流程，避免只背算法名称。</p>
                  </div>
                  <section>
                    <h3>学习目标</h3>
                    <ul class="goal-list">
                      <li>能说明进程调度算法为什么要在公平性、响应时间和吞吐量之间取舍。</li>
                      <li>能根据题目条件判断应使用 FCFS、SJF 还是时间片轮转。</li>
                    </ul>
                  </section>
                  <section>
                    <h3>核心概念</h3>
                    <table class="concept-table">
                      <thead><tr><th>算法</th><th>关键条件</th><th>主要风险</th></tr></thead>
                      <tbody>
                        <tr><td>FCFS</td><td>按进程到达时间排序</td><td>短进程可能等待过久</td></tr>
                        <tr><td>SJF</td><td>需要比较运行时间</td><td>长作业可能饥饿</td></tr>
                      </tbody>
                    </table>
                  </section>
                  <section>
                    <h3>操作步骤</h3>
                    <ol class="step-list">
                      <li>先列出每个进程的到达时间、运行时间和优先级等条件。</li>
                      <li>再确认题目要求优化的是平均等待时间、响应时间还是公平性。</li>
                      <li>最后按进程调度算法规则画出执行顺序并回算指标。</li>
                    </ol>
                  </section>
                  <section>
                    <h3>对比例题</h3>
                    <div class="comparison-grid">
                      <div><h3>适合 FCFS</h3><p>三个进程按明确到达顺序进入，就按先来先服务计算等待时间。</p></div>
                      <div><h3>适合 SJF</h3><p>题目给出运行时间且要求平均等待时间更低时，优先比较短作业。</p></div>
                    </div>
                  </section>
                  <section>
                    <h3>易错检查</h3>
                    <ul class="check-list">
                      <li>不要把到达时间当成运行时间。</li>
                      <li>不要忽略时间片轮转中的上下文切换顺序。</li>
                    </ul>
                  </section>
                  <section>
                    <h3>复盘清单</h3>
                    <ul class="review-list">
                      <li>能否用一句话解释进程调度算法的取舍目标？</li>
                      <li>能否独立画出一组进程的调度甘特图？</li>
                    </ul>
                  </section>
                </div>
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("html");
        assertThat(content.getContent()).contains(
                "generated-resource-html",
                "<style>",
                "stack-layout",
                "学习目标",
                "核心概念",
                "操作步骤",
                "对比例题",
                "易错检查",
                "复盘清单"
        );
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"llm\"",
                "\"status\":\"passed\"",
                "\"promptVersion\":\"resource-html-v1\""
        );
    }

    @Test
    void generateHtmlFallsBackToTemplateWhenHtmlFailsValidation() {
        Resource resource = htmlResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("太短了");

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("html");
        assertThat(content.getContent()).contains("generated-resource-html", "concept-table", "复盘清单");
        assertThat(content.getContent()).doesNotContain("<script", "onclick");
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"template_fallback\"",
                "\"status\":\"needs_review\"",
                "\"promptVersion\":\"resource-html-v1\""
        );
    }

    @Test
    void generateQuizUsesStructuredLlmJsonWhenValidationPasses() throws Exception {
        Resource resource = quizResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                ```json
                {
                  "title": "进程调度算法专项练习",
                  "knowledgePoint": "进程调度算法",
                  "questions": [
                    {
                      "type": "single_choice",
                      "stem": "学习进程调度算法时，选择 FCFS 算法最需要先确认哪类信息？",
                      "options": ["进程到达时间顺序", "磁盘块大小", "页面置换次数", "文件目录深度"],
                      "answer": "进程到达时间顺序",
                      "explanation": "FCFS 按到达先后服务，因此到达时间顺序是判断执行顺序的关键。",
                      "difficulty": 2,
                      "tags": ["进程调度算法", "FCFS"]
                    },
                    {
                      "type": "single_choice",
                      "stem": "在进程调度算法中，短作业优先最容易带来什么风险？",
                      "options": ["长作业饥饿", "无法计算周转时间", "所有进程同时阻塞", "CPU 无法切换"],
                      "answer": "长作业饥饿",
                      "explanation": "短作业不断到来时，长作业可能长期得不到 CPU。",
                      "difficulty": 3,
                      "tags": ["进程调度算法", "SJF"]
                    },
                    {
                      "type": "true_false",
                      "stem": "时间片轮转属于关注响应时间的进程调度算法。",
                      "options": ["正确", "错误"],
                      "answer": "正确",
                      "explanation": "时间片轮转让多个就绪进程轮流获得 CPU，有利于交互式响应。",
                      "difficulty": 2,
                      "tags": ["进程调度算法"]
                    },
                    {
                      "type": "short_answer",
                      "stem": "请说明比较两个进程调度算法时，为什么不能只看平均等待时间。",
                      "referenceAnswer": "因为调度算法还要综合响应时间、公平性、吞吐量和是否可能导致饥饿等指标，平均等待时间只能反映其中一个侧面。",
                      "explanation": "进程调度算法是多目标权衡，单一指标会掩盖公平性和响应速度问题。",
                      "difficulty": 4,
                      "tags": ["进程调度算法", "评价指标"]
                    }
                  ]
                }
                ```
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("json");
        JsonNode root = OBJECT_MAPPER.readTree(content.getContent());
        assertThat(root.get("knowledgePoint").asText()).isEqualTo("进程调度算法");
        assertThat(root.get("questions")).hasSize(4);
        assertThat(root.get("questions").get(0).get("options")).hasSize(4);
        assertThat(root.get("questions").get(3).get("referenceAnswer").asText()).contains("响应时间");
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"llm\"",
                "\"status\":\"passed\"",
                "\"promptVersion\":\"resource-quiz-v1\""
        );
    }

    @Test
    void generateQuizFallsBackToTemplateWhenLlmJsonFailsValidation() {
        Resource resource = quizResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                {"title":"进程调度算法练习","questions":[{"type":"single_choice","stem":"选哪个？"}]}
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("json");
        assertThat(content.getContent()).contains("学习「进程调度算法」时，第一步最应该确认什么？");
        assertThat(content.getContent()).doesNotContain("选哪个？");
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"template_fallback\"",
                "\"status\":\"needs_review\"",
                "\"promptVersion\":\"resource-quiz-v1\""
        );
    }

    @Test
    void generateMindMapUsesLlmMermaidWhenValidationPasses() {
        Resource resource = mindMapResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                # 进程调度算法思维导图

                这张图用于把进程调度算法的定义、条件、应用和复盘路径压缩成一张可检查结构图。

                ```mermaid
                mindmap
                  root((进程调度算法))
                    核心定义
                      就绪进程选择
                      CPU 分配策略
                    适用条件
                      到达时间明确
                      运行时间可比较
                    典型应用
                      FCFS 排序
                      SJF 比较
                    易错点
                      混淆等待时间
                      忽略饥饿风险
                    复盘路径
                      说出评价指标
                      对比算法取舍
                ```
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("markdown");
        assertThat(content.getContent()).contains("```mermaid", "mindmap", "root((进程调度算法))", "易错点", "复盘路径");
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"llm\"",
                "\"status\":\"passed\"",
                "\"promptVersion\":\"resource-mind-map-v1\""
        );
    }

    @Test
    void generateMindMapFallsBackToTemplateWhenMermaidFailsValidation() {
        Resource resource = mindMapResource();
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);
        when(contextBuilder.build(7L, resource)).thenReturn(context(resource));
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                # 进程调度算法

                这里只写一段普通说明，没有 Mermaid 思维导图。
                """);

        ResourceContent content = generator.generate(resource, 7L);

        assertThat(content.getContentType()).isEqualTo("markdown");
        assertThat(content.getContent()).contains("```mermaid", "root((进程调度算法))", "核心定义");
        assertThat(content.getContent()).doesNotContain("这里只写一段普通说明");
        assertThat(content.getReviewResult()).contains(
                "\"source\":\"template_fallback\"",
                "\"status\":\"needs_review\"",
                "\"promptVersion\":\"resource-mind-map-v1\""
        );
    }

    private Resource docResource() {
        Resource resource = new Resource();
        resource.setId(99L);
        resource.setSpaceId(10L);
        resource.setTaskId(20L);
        resource.setTitle("讲义文档：进程调度算法");
        resource.setDescription("围绕当前任务生成讲义");
        resource.setResourceType("doc");
        resource.setMetadata("{\"knowledgePoint\":\"进程调度算法\",\"targetMinutes\":30}");
        return resource;
    }

    private Resource quizResource() {
        Resource resource = docResource();
        resource.setId(100L);
        resource.setTitle("专项练习：进程调度算法");
        resource.setDescription("围绕当前任务生成练习题");
        resource.setResourceType("quiz");
        return resource;
    }

    private Resource mindMapResource() {
        Resource resource = docResource();
        resource.setId(101L);
        resource.setTitle("思维导图：进程调度算法");
        resource.setDescription("梳理当前任务的概念层级");
        resource.setResourceType("mind_map");
        return resource;
    }

    private Resource htmlResource() {
        Resource resource = docResource();
        resource.setId(102L);
        resource.setTitle("交互知识页：进程调度算法");
        resource.setDescription("围绕当前任务生成 HTML 学习页");
        resource.setResourceType("html");
        return resource;
    }

    private ResourceGenerationContext context(Resource resource) {
        LearningSpaceVO space = new LearningSpaceVO();
        space.setId(10L);
        space.setName("操作系统复习");
        space.setGoals("[\"掌握操作系统核心知识\"]");

        Task task = new Task();
        task.setId(20L);
        task.setTitle("进程调度算法");
        task.setDescription("理解 FCFS、SJF 和时间片轮转");

        return new ResourceGenerationContext(
                resource,
                space,
                task,
                null,
                "进程调度算法",
                "围绕当前任务生成可直接学习的讲义",
                "学习",
                "讲清楚并配例题",
                "task",
                30,
                Map.of("knowledgePoint", "进程调度算法", "targetMinutes", 30)
        );
    }
}
