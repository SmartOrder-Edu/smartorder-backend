package com.smartorder.edu.student.service.impl;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import com.smartorder.edu.student.service.IStudentProfileService.DimensionUpdateDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentProfileServiceImplTest {

    @Test
    void updatesCurrentUserProfileWithoutTrustingTransportIdentityFields() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(7L);
        existing.setId(10L);
        existing.setProfileVersion(3);
        existing.setLearningBackground(new HashMap<>(Map.of(
                "education", new HashMap<>(Map.of("grade", "SOPHOMORE"))
        )));
        when(mapper.selectByUserIdForUpdate(7L)).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        StudentProfile patch = new StudentProfile();
        patch.setId(999L);
        patch.setUserId(999L);
        patch.setProfileVersion(99);
        patch.setLearningBackground(Map.of(
                "education", Map.of("major_name", "软件工程")
        ));

        StudentProfileServiceImpl service = new StudentProfileServiceImpl(mapper);
        StudentProfile result = service.updateProfile(7L, patch);

        assertEquals(10L, result.getId());
        assertEquals(7L, result.getUserId());
        assertEquals(4, result.getProfileVersion());
        assertEquals("SOPHOMORE", nestedValue(result.getLearningBackground(), "education", "grade"));
        assertEquals("软件工程", nestedValue(result.getLearningBackground(), "education", "major_name"));
        assertEquals(new BigDecimal("14.29"), result.getProfileCompleteness());
        verify(mapper).updateById(result);
    }

    @Test
    void initializesNullDimensionBeforeIncrementalMerge() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(8L);
        existing.setId(11L);
        existing.setProfileVersion(1);
        existing.setAbilityProfile(null);
        when(mapper.selectByUserIdForUpdate(8L)).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        StudentProfileServiceImpl service = new StudentProfileServiceImpl(mapper);
        StudentProfile result = service.updateProfileDimension(
                8L,
                "ability_profile",
                Map.of("overall_score", 72),
                "完成能力测评"
        );

        assertEquals(72, result.getAbilityProfile().get("overall_score"));
        assertEquals(2, result.getProfileVersion());
        assertTrue(result.getDimensionMetadata().containsKey("ability_profile"));
    }

    @Test
    void rejectsWholeBatchBeforeWritingWhenDimensionIsUnsupported() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfileServiceImpl service = new StudentProfileServiceImpl(mapper);

        List<DimensionUpdateDTO> updates = List.of(
                new DimensionUpdateDTO("learning_goals", Map.of("goal", "通过考试"), "用户明确目标"),
                new DimensionUpdateDTO("unknown_dimension", Map.of("value", 1), "非法维度")
        );

        assertThrows(BusinessException.class, () -> service.updateProfileDimensions(9L, updates));
        verify(mapper, never()).selectByUserIdForUpdate(any());
        verify(mapper, never()).updateById(any(StudentProfile.class));
        verify(mapper, never()).insert(any(StudentProfile.class));
    }

    @Test
    void initializationMergesOnboardingDataWithExistingProfile() {
        StudentProfileMapper mapper = mock(StudentProfileMapper.class);
        StudentProfile existing = emptyProfile(10L);
        existing.setId(12L);
        existing.setProfileVersion(2);
        existing.setLearningBackground(new HashMap<>(Map.of("verified_level", "BASIC")));
        when(mapper.selectByUserIdForUpdate(10L)).thenReturn(existing);
        when(mapper.updateById(any(StudentProfile.class))).thenReturn(1);

        ProfileInitDTO dto = new ProfileInitDTO();
        dto.setIdentity("undergraduate");
        dto.setGrade("sophomore");
        dto.setDirection("cs");
        dto.setGoal("competition");

        StudentProfileServiceImpl service = new StudentProfileServiceImpl(mapper);
        StudentProfile result = service.initProfile(10L, dto);

        assertEquals("BASIC", result.getLearningBackground().get("verified_level"));
        assertEquals("undergraduate", result.getLearningBackground().get("identity_type"));
        assertEquals("competition", result.getLearningGoals().get("primary_goal"));
        assertEquals(3, result.getProfileVersion());
        assertEquals(new BigDecimal("28.57"), result.getProfileCompleteness());
        assertEquals(new BigDecimal("30.00"), result.getProfileConfidenceScore());
    }

    private StudentProfile emptyProfile(Long userId) {
        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setProfileVersion(0);
        profile.setLearningBackground(new HashMap<>());
        profile.setLearningGoals(new HashMap<>());
        profile.setAbilityProfile(new HashMap<>());
        profile.setCommonMisconceptions(new HashMap<>());
        profile.setLearningPreference(new HashMap<>());
        profile.setMetacognitiveProfile(new HashMap<>());
        profile.setSubjectProfiles(new HashMap<>());
        profile.setDimensionMetadata(new HashMap<>());
        profile.setProfileCompleteness(BigDecimal.ZERO);
        profile.setProfileConfidenceScore(BigDecimal.ZERO);
        return profile;
    }

    @SuppressWarnings("unchecked")
    private Object nestedValue(Map<String, Object> source, String parentKey, String childKey) {
        return ((Map<String, Object>) source.get(parentKey)).get(childKey);
    }
}
