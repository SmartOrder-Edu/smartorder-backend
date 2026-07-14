package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;

/**
 * 测评答案提交与判分服务接口
 *
 * @author 马强
 * @since 2026-07-07
 */
public interface IEvaluationSubmitService {

    /**
     * 提交答案并判分。
     * <p>客观题（单选/多选/判断/填空）程序直接判分，主观题（简答/编程/场景/SQL/多模态）
     * 先标记为待判分，后台异步调用 AI 判分。</p>
     *
     * @param taskId  任务ID，作为答题批次标识写入 session_id 字段
     * @param spaceId 学习空间ID
     * @param userId  用户ID
     * @param dto     答案列表
     * @return 判分结果（含客观题即时得分 + 主观题待判分数量）
     */
    TaskSubmitResultVO submit(Long taskId, Long spaceId, Long userId, SubmitAnswersDTO dto);

    /**
     * 查询判分结果（用于主观题轮询）。
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 最新判分结果
     */
    TaskSubmitResultVO getResult(Long taskId, Long userId);
}
