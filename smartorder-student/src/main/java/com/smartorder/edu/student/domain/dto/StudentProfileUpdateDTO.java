package com.smartorder.edu.student.domain.dto;

import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import com.smartorder.edu.student.domain.dto.student.CommonMisconceptionsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningBackgroundDTO;
import com.smartorder.edu.student.domain.dto.student.LearningGoalsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningPreferenceDTO;
import com.smartorder.edu.student.domain.dto.student.MetacognitiveProfileDTO;
import com.smartorder.edu.student.domain.dto.student.dto.SubjectProfilesDTO;
import lombok.Data;

/**
 * 学生画像更新请求。
 *
 * <p>只暴露允许由调用方提交的画像维度，不接受主键、用户ID、版本号和系统评分字段。</p>
 */
@Data
public class StudentProfileUpdateDTO {

    private LearningBackgroundDTO learningBackground;

    private LearningGoalsDTO learningGoals;

    private AbilityProfileDTO abilityProfile;

    private CommonMisconceptionsDTO commonMisconceptions;

    private LearningPreferenceDTO learningPreference;

    private MetacognitiveProfileDTO metacognitiveProfile;

    private SubjectProfilesDTO subjectProfiles;
}
