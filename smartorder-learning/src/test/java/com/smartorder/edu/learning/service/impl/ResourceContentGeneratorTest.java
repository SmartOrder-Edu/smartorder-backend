package com.smartorder.edu.learning.service.impl;

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
