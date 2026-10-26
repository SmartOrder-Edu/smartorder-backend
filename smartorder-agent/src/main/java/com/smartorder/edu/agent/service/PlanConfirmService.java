package com.smartorder.edu.agent.service;

import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartorder.edu.learning.service.*;

import java.util.*;

/**
 * 学习计划确认服务——用户审阅学习计划后的执行阶段。
 *
 * <p>迭代一：草案完整物化。落库结构：
 * <ul>
 *   <li>每个 phase → 一个 stage 类型的 LearningPathNode（带 plannedDurationDays）</li>
 *   <li>每个 phase.knowledgePoints[i] → 一个 knowledge_point 类型 LearningPathNode（parentNodeId 指向 stage，带 kpUid）</li>
 *   <li>knowledgeGraph.nodes → KnowledgeGraphNode（带 kpUid，与 path 节点共享）</li>
 *   <li>phase.resources → Resource(status='planned', source='ai', isAiGenerated=1)，挂在 stage 节点上</li>
 *   <li>edge：stage prerequisite_of stage、stage contains kp、kp prerequisite_of kp（同 phase 内顺序）</li>
 * </ul>
 *
 * <p>幂等：通过行锁 (selectByDraftIdForUpdate) + confirmAndBindSpace 原子更新保证同一草稿只创建一个学习空间。
 *
 * <p>不预生成每日学习任务（由 Master Agent 根据当日情况动态调度）。
 */
public interface PlanConfirmService {

    LearningSpaceVO createLearningSpace(String draftId, Long userId, String conversationId, SseEmitter emitter, String traceId);
}
