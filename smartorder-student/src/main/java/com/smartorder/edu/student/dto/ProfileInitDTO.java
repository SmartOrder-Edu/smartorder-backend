package com.smartorder.edu.student.dto;

import lombok.Data;

/**
 * 引导页画像初始化请求 DTO
 *
 * @author 谢光湘
 * @since 2026-05-19
 */
@Data
public class ProfileInitDTO {

    /** 身份：undergraduate|graduate|self|career */
    private String identity;

    /** 年级：freshman|sophomore|junior|senior|g1|g2|g3 */
    private String grade;

    /** 学习方向：ai|cs|ds|ee|business|humanities|design|other */
    private String direction;

    /** 学习方向补充说明 */
    private String directionDetail;

    /** 当前基础：beginner|some|course|advanced */
    private String level;

    /** 当前基础补充说明 */
    private String levelDetail;

    /** 近期目标：exam|project|skill|grad|competition */
    private String goal;

    /** 近期目标补充说明 */
    private String goalDetail;

    /** 目标截止时间：1week|1month|semester|longterm */
    private String goalDeadline;

    /** 每日学习时间：lt30|30to60|1to2|2to3|gt3 */
    private String dailyStudyTime;

    /** 自由文本自我描述 */
    private String selfDescription;
}
