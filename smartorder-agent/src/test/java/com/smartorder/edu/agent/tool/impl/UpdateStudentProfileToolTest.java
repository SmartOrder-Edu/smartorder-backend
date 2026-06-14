package com.smartorder.edu.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.student.service.IStudentProfileService;
import com.smartorder.edu.student.service.IStudentProfileService.DimensionUpdateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UpdateStudentProfileToolTest {

    private IStudentProfileService profileService;
    private UpdateStudentProfileTool tool;

    @BeforeEach
    void setUp() {
        profileService = mock(IStudentProfileService.class);
        tool = new UpdateStudentProfileTool(profileService);
    }

    @Test
    void shouldUpdateSingleDimensionWhenCurrentMessageContainsExplicitWeaknessEvidence() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "weakness_profile",
                    "delta": {"weak_points": [{"topic": "死锁"}]},
                    "evidence": "我总是搞不懂死锁",
                    "confidence": 0.9,
                    "reason": "用户明确表达对死锁概念长期理解困难"
                  }]
                }
                """);

        Map<String, Object> result = execute(request, "我总是搞不懂死锁，能再讲清楚一点吗");

        assertEquals(true, result.get("success"));
        verify(profileService).updateProfileDimension(
                eq(7L),
                eq("weakness_profile"),
                any(),
                eq("用户明确表达对死锁概念长期理解困难")
        );
    }

    @Test
    void shouldBatchMultipleValidDimensionsInOneServiceCall() {
        Object request = request("""
                {
                  "dimensionUpdate": [
                    {
                      "dimensionKey": "preference_profile",
                      "delta": {"preferred_explanation": "diagram"},
                      "evidence": "我更喜欢通过流程图理解概念",
                      "confidence": 0.92,
                      "reason": "用户明确表达稳定的图示学习偏好"
                    },
                    {
                      "dimensionKey": "behavior_profile",
                      "delta": {"effective_period": "evening"},
                      "evidence": "我通常晚上学习效率更高",
                      "confidence": 0.88,
                      "reason": "用户明确表达稳定的高效学习时段"
                    }
                  ]
                }
                """);

        Map<String, Object> result = execute(
                request,
                "我更喜欢通过流程图理解概念，而且我通常晚上学习效率更高"
        );

        assertEquals(true, result.get("success"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DimensionUpdateDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(profileService).updateProfileDimensions(eq(7L), captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void shouldRejectEvidenceThatIsNotInCurrentUserMessage() {
        Object request = weaknessRequest("我总是搞不懂死锁", 0.9, "明确薄弱点");

        Map<String, Object> result = execute(request, "请解释一下死锁");

        assertRejected(result, "当前用户消息");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectConfidenceBelowThreshold() {
        Object request = weaknessRequest("我总是搞不懂死锁", 0.79, "明确薄弱点");

        Map<String, Object> result = execute(request, "我总是搞不懂死锁");

        assertRejected(result, "confidence");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectNonFiniteConfidence() {
        UpdateStudentProfileTool.UpdateStudentProfileRequest request =
                new UpdateStudentProfileTool.UpdateStudentProfileRequest();
        UpdateStudentProfileTool.UpdateStudentProfileRequest.DimensionUpdate update =
                new UpdateStudentProfileTool.UpdateStudentProfileRequest.DimensionUpdate();
        update.setDimensionKey("weakness_profile");
        update.setDelta(Map.of("weak_points", List.of(Map.of("topic", "死锁"))));
        update.setEvidence("我总是搞不懂死锁");
        update.setConfidence(Double.NaN);
        update.setReason("明确薄弱点");
        request.setDimensionUpdate(List.of(update));

        Map<String, Object> result = execute(request, "我总是搞不懂死锁");

        assertRejected(result, "confidence");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectOrdinaryExplanationRequestAsWeaknessEvidence() {
        Object request = weaknessRequest("解释一下死锁", 0.95, "用户询问死锁");

        Map<String, Object> result = execute(request, "解释一下死锁");

        assertRejected(result, "明确自述");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectOneOffDiagramRequestAsStablePreferenceEvidence() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "preference_profile",
                    "delta": {"preferred_explanation": "diagram"},
                    "evidence": "这次用流程图解释",
                    "confidence": 0.95,
                    "reason": "用户要求图示"
                  }]
                }
                """);

        Map<String, Object> result = execute(request, "这次用流程图解释");

        assertRejected(result, "明确自述");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectBlankReason() {
        Object request = weaknessRequest("我总是搞不懂死锁", 0.9, " ");

        Map<String, Object> result = execute(request, "我总是搞不懂死锁");

        assertRejected(result, "reason");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldRejectUnsupportedDimension() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "private_profile",
                    "delta": {"secret": "value"},
                    "evidence": "我是计算机专业学生",
                    "confidence": 0.95,
                    "reason": "用户自述专业"
                  }]
                }
                """);

        Map<String, Object> result = execute(request, "我是计算机专业学生");

        assertRejected(result, "不支持的维度");
        verifyNoInteractions(profileService);
    }

    @Test
    void shouldAcceptExplicitPostgraduateExamGoal() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "basic_profile",
                    "delta": {"learning_goal": "考研"},
                    "evidence": "我准备考研",
                    "confidence": 0.95,
                    "reason": "用户明确表达考研目标"
                  }]
                }
                """);

        Map<String, Object> result = execute(request, "我准备考研");

        assertEquals(true, result.get("success"));
        verify(profileService).updateProfileDimension(
                eq(7L),
                eq("basic_profile"),
                any(),
                eq("用户明确表达考研目标")
        );
    }

    @Test
    void shouldAcceptExplicitDataStructureLearningGap() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "weakness_profile",
                    "delta": {"weak_points": [{"topic": "数据结构", "weakness_type": "foundation_gap"}]},
                    "evidence": "大二的时候没有怎么学会数据结构",
                    "confidence": 0.92,
                    "reason": "用户明确说明数据结构基础学习不足"
                  }]
                }
                """);

        Map<String, Object> result = execute(
                request,
                "我准备考研，大二的时候没有怎么学会数据结构"
        );

        assertEquals(true, result.get("success"));
        verify(profileService).updateProfileDimension(
                eq(7L),
                eq("weakness_profile"),
                any(),
                eq("用户明确说明数据结构基础学习不足")
        );
    }

    @Test
    void shouldRejectOneOffActionAsBasicProfileGoal() {
        Object request = request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "basic_profile",
                    "delta": {"learning_goal": "生成PPT"},
                    "evidence": "我准备让你生成PPT",
                    "confidence": 0.95,
                    "reason": "用户准备生成PPT"
                  }]
                }
                """);

        Map<String, Object> result = execute(request, "我准备让你生成PPT");

        assertRejected(result, "明确自述");
        verifyNoInteractions(profileService);
    }

    private Object weaknessRequest(String evidence, double confidence, String reason) {
        return request("""
                {
                  "dimensionUpdate": [{
                    "dimensionKey": "weakness_profile",
                    "delta": {"weak_points": [{"topic": "死锁"}]},
                    "evidence": "%s",
                    "confidence": %s,
                    "reason": "%s"
                  }]
                }
                """.formatted(evidence, confidence, reason));
    }

    private Object request(String json) {
        return JSON.parseObject(json, UpdateStudentProfileTool.UpdateStudentProfileRequest.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Object request, String currentUserMessage) {
        ToolInvokeContext context = ToolInvokeContext.builder()
                .userId(7L)
                .currentUserMessage(currentUserMessage)
                .build();
        return (Map<String, Object>) tool.execute(request, context);
    }

    private void assertRejected(Map<String, Object> result, String expectedErrorPart) {
        assertFalse(Boolean.TRUE.equals(result.get("success")));
        assertTrue(Boolean.TRUE.equals(result.get("rejected")));
        assertTrue(String.valueOf(result.get("error")).contains(expectedErrorPart));
    }
}
