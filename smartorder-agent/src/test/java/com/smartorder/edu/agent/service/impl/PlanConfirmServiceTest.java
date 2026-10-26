package com.smartorder.edu.agent.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanConfirmServiceTest {

    @Test
    void normalizeImportanceAcceptsChineseLevelsAndNumericValues() {
        assertThat(PlanConfirmServiceImpl.normalizeImportance("高")).isEqualTo(4);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("核心")).isEqualTo(5);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("中")).isEqualTo(3);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("低")).isEqualTo(2);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("了解")).isEqualTo(1);

        assertThat(PlanConfirmServiceImpl.normalizeImportance(4)).isEqualTo(4);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("4")).isEqualTo(4);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("4.6")).isEqualTo(5);
    }

    @Test
    void normalizeImportanceClampsOutOfRangeAndDefaultsUnknownValues() {
        assertThat(PlanConfirmServiceImpl.normalizeImportance(0)).isEqualTo(1);
        assertThat(PlanConfirmServiceImpl.normalizeImportance(9)).isEqualTo(5);
        assertThat(PlanConfirmServiceImpl.normalizeImportance(null)).isEqualTo(3);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("")).isEqualTo(3);
        assertThat(PlanConfirmServiceImpl.normalizeImportance("not sure")).isEqualTo(3);
    }
}
