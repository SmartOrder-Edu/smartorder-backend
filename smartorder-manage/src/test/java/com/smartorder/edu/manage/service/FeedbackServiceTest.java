package com.smartorder.edu.manage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import com.smartorder.edu.manage.domain.dto.FeedbackQueryDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackReviewDTO;
import com.smartorder.edu.manage.domain.dto.FeedbackSubmitDTO;
import com.smartorder.edu.manage.domain.po.Feedback;
import com.smartorder.edu.manage.domain.vo.FeedbackVO;
import com.smartorder.edu.manage.listener.FeedbackSubmittedEvent;
import com.smartorder.edu.manage.mapper.FeedbackMapper;
import com.smartorder.edu.manage.service.impl.FeedbackServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import com.smartorder.edu.manage.producer.PrizeMessageProducer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FeedbackServiceTest {

    @Mock
    private FeedbackAiAnalysisService aiAnalysisService;

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private PrizeService prizeService;

    @Mock
    private PrizeMessageProducer prizeMessageProducer;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private FeedbackServiceImpl feedbackService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(feedbackService, "baseMapper", feedbackMapper);
        
        // 初始化 MyBatis-Plus TableInfo，防止在单元测试中调用 TableInfoHelper 报空指针
        if (TableInfoHelper.getTableInfo(Feedback.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Feedback.class);
        }
        
        // Mock 插入
        when(feedbackMapper.insert(any(Feedback.class))).thenAnswer(invocation -> {
            Feedback entity = invocation.getArgument(0);
            entity.setId(100L); // 模拟插入后主键返回
            return 1;
        });
    }

    @Test
    public void testSubmitFeedback_Valid() {
        FeedbackSubmitDTO dto = new FeedbackSubmitDTO();
        dto.setType(2);
        dto.setContent("这个功能非常好，建议增加夜间模式。");
        dto.setImageUrls(List.of("http://example.com/1.png"));

        FeedbackVO vo = feedbackService.submitFeedback(dto, 1L);

        assertNotNull(vo);
        assertEquals(1L, vo.getUserId());
        assertEquals(2, vo.getType());
        assertEquals(0, vo.getStatus()); // 0表示待AI初审（持久化优先）
        assertNull(vo.getAiAnalysisResult());
        
        verify(feedbackMapper, times(1)).insert(any(Feedback.class));
        verify(eventPublisher, times(1)).publishEvent(any(FeedbackSubmittedEvent.class));
    }

    @Test
    public void testSubmitFeedback_Invalid() {
        FeedbackSubmitDTO dto = new FeedbackSubmitDTO();
        dto.setType(1);
        dto.setContent("啊啊啊啊啊啊垃圾");

        FeedbackVO vo = feedbackService.submitFeedback(dto, 2L);

        assertNotNull(vo);
        assertEquals(0, vo.getStatus()); // 初始状态为 0 待初审
        assertNull(vo.getAiAnalysisResult());
        
        verify(feedbackMapper, times(1)).insert(any(Feedback.class));
        verify(eventPublisher, times(1)).publishEvent(any(FeedbackSubmittedEvent.class));
    }

    @Test
    public void testAsyncAiReview_Valid() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);
        po.setType(2);
        po.setContent("这个功能非常好，建议增加夜间模式。");
        po.setStatus(0); // 待AI初审

        when(feedbackMapper.selectById(100L)).thenReturn(po);
        when(aiAnalysisService.analyzeFeedback(anyString(), anyInt()))
                .thenReturn("{\"isValid\": true, \"reason\": \"合理的建议\", \"score\": 85}");

        feedbackService.asyncAiReview(100L);

        assertEquals(1, po.getStatus()); // AI初审通过 -> 待人工复核 (1)
        assertTrue(po.getAiAnalysisResult().contains("true"));
        verify(feedbackMapper, times(1)).updateById(po);
    }

    @Test
    public void testAsyncAiReview_Invalid() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(2L);
        po.setType(1);
        po.setContent("啊啊啊啊啊啊垃圾");
        po.setStatus(0); // 待AI初审

        when(feedbackMapper.selectById(100L)).thenReturn(po);
        when(aiAnalysisService.analyzeFeedback(anyString(), anyInt()))
                .thenReturn("{\"isValid\": false, \"reason\": \"包含灌水及辱骂\", \"score\": 10}");

        feedbackService.asyncAiReview(100L);

        assertEquals(2, po.getStatus()); // AI初审驳回 -> AI过滤驳回 (2)
        assertTrue(po.getReviewRemark().contains("系统AI自动拦截: 包含灌水及辱骂"));
        verify(feedbackMapper, times(1)).updateById(po);
    }

    @Test
    public void testQueryFeedbackPage() {
        FeedbackQueryDTO queryDTO = new FeedbackQueryDTO();
        queryDTO.setPage(1);
        queryDTO.setSize(10);
        queryDTO.setType(2);

        Page<Feedback> poPage = new Page<>(1, 10);
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);
        po.setType(2);
        po.setContent("测试分页反馈");
        po.setImageUrls("img1.png,img2.png");
        po.setStatus(1);
        poPage.setRecords(List.of(po));
        poPage.setTotal(1L);

        when(feedbackMapper.selectPage(any(), any())).thenReturn(poPage);

        Page<FeedbackVO> voPage = feedbackService.queryFeedbackPage(queryDTO);

        assertNotNull(voPage);
        assertEquals(1, voPage.getTotal());
        assertEquals(1, voPage.getRecords().size());
        assertEquals("测试分页反馈", voPage.getRecords().get(0).getContent());
        assertEquals(List.of("img1.png", "img2.png"), voPage.getRecords().get(0).getImageUrls());
    }

    @Test
    public void testReviewFeedback_PassWithReply() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);
        po.setType(2);
        po.setContent("高质量建议");
        po.setStatus(1); // 待人工复核

        when(feedbackMapper.selectById(100L)).thenReturn(po);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);

        FeedbackReviewDTO reviewDTO = new FeedbackReviewDTO();
        reviewDTO.setStatus(3); // 3=人工审核通过
        reviewDTO.setReviewRemark("非常实用的建议！");
        reviewDTO.setReplyContent("亲爱的学生，我们已经将夜间模式列入排期，感谢反馈！");

        // Mock prizeService & producer
        when(prizeService.allocatePrize(100L, 1L)).thenReturn("PRIZE_CODE_XYZ");

        FeedbackVO vo = feedbackService.reviewFeedback(100L, reviewDTO);

        assertNotNull(vo);
        assertEquals(3, vo.getStatus());
        assertEquals("非常实用的建议！", vo.getReviewRemark());
        assertEquals("亲爱的学生，我们已经将夜间模式列入排期，感谢反馈！", vo.getReplyContent());
        assertNotNull(vo.getReplyTime());
        verify(feedbackMapper, times(1)).updateById(any(Feedback.class));
        verify(prizeService, times(1)).allocatePrize(100L, 1L);
        verify(prizeMessageProducer, times(1)).sendPrizeMailMessage(any());
    }

    @Test
    public void testDeleteFeedback_Success() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);

        when(feedbackMapper.selectById(100L)).thenReturn(po);
        when(feedbackMapper.deleteById(100L)).thenReturn(1);

        // 学生 (role=0) 只能删除属于自己的反馈
        assertDoesNotThrow(() -> feedbackService.deleteFeedback(100L, 1L, 0));

        // 校验如果是非本人 (role=0) 则报错
        assertThrows(IllegalStateException.class, () -> feedbackService.deleteFeedback(100L, 2L, 0));

        // 校验管理员 (role=1) 可以强行删除任何人记录
        assertDoesNotThrow(() -> feedbackService.deleteFeedback(100L, 999L, 1));
    }

    @Test
    public void testUpdateFeedback_Success() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);
        po.setType(2);
        po.setContent("旧建议");
        po.setStatus(1); // AI初审通过待复核

        when(feedbackMapper.selectById(100L)).thenReturn(po);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);

        FeedbackSubmitDTO updateDTO = new FeedbackSubmitDTO();
        updateDTO.setType(2);
        updateDTO.setContent("新修改的反馈内容");

        // 学生 (role=0) 修改自己待审的数据成功，并重新发布 AI 审核事件
        FeedbackVO vo = feedbackService.updateFeedback(100L, updateDTO, 1L, 0);

        assertNotNull(vo);
        assertEquals("新修改的反馈内容", vo.getContent());
        assertEquals(0, vo.getStatus()); // 重新变更为 0（待初审）
        verify(eventPublisher, times(1)).publishEvent(any(FeedbackSubmittedEvent.class));
    }

    @Test
    public void testUpdateFeedback_Forbidden() {
        Feedback po = new Feedback();
        po.setId(100L);
        po.setUserId(1L);
        po.setType(2);
        po.setStatus(3); // 状态为3（人工已审核通过）

        when(feedbackMapper.selectById(100L)).thenReturn(po);

        FeedbackSubmitDTO updateDTO = new FeedbackSubmitDTO();
        updateDTO.setType(2);
        updateDTO.setContent("尝试篡改");

        // 学生修改已过审的数据应该失败
        assertThrows(IllegalStateException.class, () -> feedbackService.updateFeedback(100L, updateDTO, 1L, 0));
    }

}
