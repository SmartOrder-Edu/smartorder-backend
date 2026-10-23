package com.smartorder.edu.student.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import com.smartorder.edu.student.domain.dto.student.CommonMisconceptionsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningBackgroundDTO;
import com.smartorder.edu.student.domain.dto.student.LearningGoalsDTO;
import com.smartorder.edu.student.domain.dto.student.LearningPreferenceDTO;
import com.smartorder.edu.student.domain.dto.student.MetacognitiveProfileDTO;
import com.smartorder.edu.student.domain.dto.student.dto.SubjectProfilesDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.mapper.StudentProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentProfileServiceImplTest {

    @Mock
    private StudentProfileMapper studentProfileMapper;

    @Test
    void getByUserIdCreatesDisplayableProfileWhenNoProfileExists() {
        when(studentProfileMapper.selectOne(any())).thenReturn(null);
        when(studentProfileMapper.insert(any(StudentProfile.class))).thenAnswer(invocation -> {
            StudentProfile profile = invocation.getArgument(0);
            profile.setId(1001L);
            return 1;
        });
        StudentProfileServiceImpl service = new StudentProfileServiceImpl(studentProfileMapper, new ObjectMapper());

        StudentProfile profile = service.getByUserId(77L);

        assertEquals(77L, profile.getUserId());
        assertEquals(0, profile.getProfileVersion());
        assertEquals(new BigDecimal("0.00"), profile.getProfileConfidenceScore());
        assertNotNull(profile.getLearningBackground());
        assertNotNull(profile.getLearningGoals());
        assertNotNull(profile.getAbilityProfile());
        assertNotNull(profile.getCommonMisconceptions());
        assertNotNull(profile.getLearningPreference());
        assertNotNull(profile.getMetacognitiveProfile());
        assertNotNull(profile.getSubjectProfiles());
        assertEquals(6, profile.getAbilityProfile().getAbilities().size());
        assertEquals(4, profile.getLearningPreference().getResourcePreferences().size());
        assertEquals(4, profile.getMetacognitiveProfile().getDimensions().size());
        assertTrue(profile.getProfileCompleteness() > 0);

        ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
        verify(studentProfileMapper).insert(captor.capture());
        assertNotNull(captor.getValue().getLearningGoals().getGoals().get("PROFILE_BOOTSTRAP"));
    }

    @Test
    void getByUserIdKeepsExistingStructuredProfileUnchanged() {
        StudentProfile existing = new StudentProfile();
        existing.setId(2002L);
        existing.setUserId(88L);
        existing.setProfileVersion(3);
        existing.setProfileConfidenceScore(new BigDecimal("45.00"));
        existing.setLearningBackground(new LearningBackgroundDTO());
        LearningGoalsDTO goals = new LearningGoalsDTO();
        goals.setGoals(Map.of("goal", new LearningGoalsDTO.GoalDTO()));
        existing.setLearningGoals(goals);
        AbilityProfileDTO ability = new AbilityProfileDTO();
        ability.setAbilities(Map.of("ability", new AbilityProfileDTO.AbilityItemDTO()));
        existing.setAbilityProfile(ability);
        CommonMisconceptionsDTO misconceptions = new CommonMisconceptionsDTO();
        misconceptions.setPatterns(new LinkedHashMap<>());
        existing.setCommonMisconceptions(misconceptions);
        LearningPreferenceDTO preference = new LearningPreferenceDTO();
        preference.setResourcePreferences(Map.of("VIDEO", new LearningPreferenceDTO.ResourcePreferenceDTO()));
        existing.setLearningPreference(preference);
        MetacognitiveProfileDTO metacognitive = new MetacognitiveProfileDTO();
        metacognitive.setDimensions(Map.of("PLANNING", new MetacognitiveProfileDTO.MetaDimensionDTO()));
        existing.setMetacognitiveProfile(metacognitive);
        existing.setSubjectProfiles(new SubjectProfilesDTO());
        when(studentProfileMapper.selectOne(any())).thenReturn(existing);
        StudentProfileServiceImpl service = new StudentProfileServiceImpl(studentProfileMapper, new ObjectMapper());

        StudentProfile profile = service.getByUserId(88L);

        assertEquals(3, profile.getProfileVersion());
        assertEquals(new BigDecimal("45.00"), profile.getProfileConfidenceScore());
        verify(studentProfileMapper, never()).insert(any(StudentProfile.class));
        verify(studentProfileMapper, never()).updateById(any(StudentProfile.class));
    }

    @Test
    void getByUserIdHydratesExistingSparseProfileForFrontendRendering() {
        StudentProfile existing = new StudentProfile();
        existing.setId(3003L);
        existing.setUserId(99L);
        existing.setProfileVersion(2);
        existing.setProfileConfidenceScore(new BigDecimal("40.00"));
        LearningBackgroundDTO background = new LearningBackgroundDTO();
        background.setCurrentLevelDetail("已填写的真实基础信息");
        existing.setLearningBackground(background);
        when(studentProfileMapper.selectOne(any())).thenReturn(existing);
        when(studentProfileMapper.updateById(any(StudentProfile.class))).thenReturn(1);
        StudentProfileServiceImpl service = new StudentProfileServiceImpl(studentProfileMapper, new ObjectMapper());

        StudentProfile profile = service.getByUserId(99L);

        assertEquals("已填写的真实基础信息", profile.getLearningBackground().getCurrentLevelDetail());
        assertNotNull(profile.getLearningGoals());
        assertNotNull(profile.getAbilityProfile());
        assertNotNull(profile.getCommonMisconceptions());
        assertNotNull(profile.getLearningPreference());
        assertNotNull(profile.getMetacognitiveProfile());
        assertNotNull(profile.getSubjectProfiles());
        verify(studentProfileMapper).updateById(existing);
    }
}
