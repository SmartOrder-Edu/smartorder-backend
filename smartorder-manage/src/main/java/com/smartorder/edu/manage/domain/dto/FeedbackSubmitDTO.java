package com.smartorder.edu.manage.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 提交反馈 DTO
 */
@Data
@NoArgsConstructor
public class FeedbackSubmitDTO {

    /**
     * 反馈类型：1=体验评价，2=功能建议，3=Bug反馈，4=其他意见
     */
    @NotNull(message = "反馈类型不能为空")
    private Integer type;

    /**
     * 反馈标题
     */
    @Size(max = 15, message = "反馈标题必须在15字以内")
    private String title;

    /**
     * 反馈内容
     */
    @NotBlank(message = "反馈内容不能为空")
    @Size(min = 10, message = "反馈详细内容最少为10字")
    private String content;

    /**
     * 图片URLs
     */
    private List<String> imageUrls;

    /**
     * 联系方式
     */
    private String contact;
}
