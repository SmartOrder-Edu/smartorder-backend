package com.smartorder.edu.student.domain.dto;

import lombok.Data;

/**
 * 引导页画像初始化请求 DTO
 * <p>
 * 对应前端 Onboarding 三步向导，采集学生基础画像信息。
 * 后端将这些字段映射到 {@link com.smartorder.edu.student.domain.entity.StudentProfile} 的
 * learningBackground 和 learningGoals 两个维度，采用深度合并策略，不会覆盖已有数据。
 *
 * @author 谢光湘
 * @since 2026-05-19
 */
@Data
public class ProfileInitDTO {

    /** 身份：undergraduate-本科生 | graduate-研究生 | self-自学 | career-职场人 */
    private String identity;

    /** 年级：freshman-大一 | sophomore-大二 | junior-大三 | senior-大四 | g1-研一 | g2-研二 | g3-研三 */
    private String grade;

    /** 学习方向：ai-人工智能 | cs-计算机 | ds-数据科学 | ee-电子电气 | business-商科 | humanities-人文 | design-设计 | other-其他 */
    private String direction;

    /** 学习方向补充说明，direction 为 other 时由用户自由填写（前端限30字） */
    private String directionDetail;

    /** 当前基础水平：beginner-零基础 | some-了解一些 | course-学过相关课程 | advanced-较深入 */
    private String level;

    /** 当前基础补充说明，用户对自身水平的额外描述 */
    private String levelDetail;

    /** 近期目标：exam-备考 | project-做项目 | skill-提升技能 | grad-毕业要求 | competition-竞赛 */
    private String goal;

    /** 近期目标补充说明，用户对目标的详细描述 */
    private String goalDetail;

    /** 目标截止时间：1week-一周内 | 1month-一个月内 | semester-本学期 | longterm-长期 */
    private String goalDeadline;

    /** 每日可投入学习时间：lt30-不足30分钟 | 30to60-30~60分钟 | 1to2-1~2小时 | 2to3-2~3小时 | gt3-3小时以上 */
    private String dailyStudyTime;

    /** 用户对自身学习情况的自由文本描述 */
    private String selfDescription;
}
