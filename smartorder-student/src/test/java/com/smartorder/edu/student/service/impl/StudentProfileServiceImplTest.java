package com.smartorder.edu.student.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileDimensionUpdateDTO;
import com.smartorder.edu.student.domain.dto.StudentProfileUpdateDTO;
import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import com.smartorder.edu.student.domain.dto.student.LearningBackgroundDTO;
import com.smartorder.edu.student.domain.dto.student.LearningGoalsDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentProfileServiceImplTest {

    @Test
    void updatesProfileFromDtoAndPreservesExistingNestedValues() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(7L);
        existing.setId(10L);
        existing.setProfileVersion(3);

        LearningBackgroundDTO currentBackground = new LearningBackgroundDTO();
        LearningBackgroundDTO.EducationDTO currentEducation = new LearningBackgroundDTO.EducationDTO();
        currentEducation.setGrade("SOPHOMORE");
        currentBackground.setEducation(currentEducation);
        existing.setLearningBackground(currentBackground);

        LearningBackgroundDTO patchBackground = new LearningBackgroundDTO();
        LearningBackgroundDTO.EducationDTO patchEducation = new LearningBackgroundDTO.EducationDTO();
        patchEducation.setMajorName("软件工程");
        patchBackground.setEducation(patchEducation);
        StudentProfileUpdateDTO patch = new StudentProfileUpdateDTO();
        patch.setLearningBackground(patchBackground);

        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        StudentProfile result = service(mapper).updateProfile(7L, patch);

        assertEquals(10L, result.getId());
        assertEquals(7L, result.getUserId());
        assertEquals(4, result.getProfileVersion());
        assertEquals("SOPHOMORE", result.getLearningBackground().getEducation().getGrade());
        assertEquals("软件工程", result.getLearningBackground().getEducation().getMajorName());
        verify(mapper).updateById(result);
    }

    @Test
    void convertsSnakeCaseDimensionDeltaIntoStrongDto() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(8L);
        existing.setId(11L);
        existing.setProfileVersion(1);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        StudentProfileDimensionUpdateDTO update = new StudentProfileDimensionUpdateDTO(
                "ability_profile",
                Map.of("overall_score", 72.0, "overall_level", "DEVELOPING"),
                "完成能力测评"
        );

        StudentProfile result = service(mapper).updateProfileDimension(8L, update);

        assertEquals(72.0, result.getAbilityProfile().getOverallScore());
        assertEquals("DEVELOPING", result.getAbilityProfile().getOverallLevel());
        assertEquals(2, result.getProfileVersion());
    }

    @Test
    void rejectsWholeBatchBeforeReadingWhenDimensionIsUnsupported() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        List<StudentProfileDimensionUpdateDTO> updates = List.of(
                new StudentProfileDimensionUpdateDTO(
                        "ability_profile",
                        Map.of("overall_score", 80),
                        "能力测评"
                ),
                new StudentProfileDimensionUpdateDTO(
                        "unknown_dimension",
                        Map.of("value", 1),
                        "非法维度"
                )
        );

        assertThrows(BusinessException.class, () -> service(mapper).updateProfileDimensions(9L, updates));
        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).updateById(any(StudentProfile.class));
        verify(mapper, never()).insert(any(StudentProfile.class));
    }

    @Test
    void initializesStrongProfileDtosAndPreservesExistingBackground() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(10L);
        existing.setId(12L);
        existing.setProfileVersion(2);

        LearningBackgroundDTO currentBackground = new LearningBackgroundDTO();
        LearningBackgroundDTO.PriorExperienceDTO priorExperience = new LearningBackgroundDTO.PriorExperienceDTO();
        LearningBackgroundDTO.ProgrammingLanguageDTO javaExperience =
                new LearningBackgroundDTO.ProgrammingLanguageDTO();
        javaExperience.setVerifiedLevel("BASIC");
        priorExperience.setProgrammingLanguages(Map.of("JAVA", javaExperience));
        currentBackground.setPriorExperience(priorExperience);
        existing.setLearningBackground(currentBackground);

        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        ProfileInitDTO dto = new ProfileInitDTO();
        dto.setIdentity("undergraduate");
        dto.setGrade("sophomore");
        dto.setDirection("cs");
        dto.setGoal("competition");
        dto.setGoalDetail("参加程序设计竞赛");
        dto.setDailyStudyTime("1to2");

        StudentProfile result = service(mapper).initProfile(10L, dto);

        assertEquals(
                "BASIC",
                result.getLearningBackground()
                        .getPriorExperience()
                        .getProgrammingLanguages()
                        .get("JAVA")
                        .getVerifiedLevel()
        );
        assertEquals("UNDERGRADUATE", result.getLearningBackground().getEducation().getEducationLevel());
        assertEquals("SOPHOMORE", result.getLearningBackground().getEducation().getGrade());
        assertEquals("CS", result.getLearningBackground().getEducation().getMajorCode());
        assertEquals(630, result.getLearningBackground().getLearningConstraints().getWeeklyAvailableMinutes());

        LearningGoalsDTO.GoalDTO goal = result.getLearningGoals()
                .getGoals()
                .get(result.getLearningGoals().getPrimaryGoalId());
        assertEquals("COMPETITION", goal.getGoalType());
        assertEquals("参加程序设计竞赛", goal.getTitle());
        assertEquals(3, result.getProfileVersion());
        assertEquals(new BigDecimal("30.00"), result.getProfileConfidenceScore());
    }

    private StudentProfileServiceImpl service(StudentProfileMapper mapper) {
        return new StudentProfileServiceImpl(mapper, new ObjectMapper());
    }

    private StudentProfile emptyProfile(Long userId) {
        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setProfileVersion(0);
        profile.setProfileConfidenceScore(new BigDecimal("0.00"));
        return profile;
    }
}
