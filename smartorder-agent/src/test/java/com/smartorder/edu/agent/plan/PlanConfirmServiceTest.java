package com.smartorder.edu.agent.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanConfirmServiceTest {

    @Test
    void normalizeImportanceAcceptsChineseLevelsAndNumericValues() {
        assertThat(PlanConfirmService.normalizeImportance("高")).isEqualTo(4);
        assertThat(PlanConfirmService.normalizeImportance("核心")).isEqualTo(5);
        assertThat(PlanConfirmService.normalizeImportance("中")).isEqualTo(3);
        assertThat(PlanConfirmService.normalizeImportance("低")).isEqualTo(2);
        assertThat(PlanConfirmService.normalizeImportance("了解")).isEqualTo(1);

        assertThat(PlanConfirmService.normalizeImportance(4)).isEqualTo(4);
        assertThat(PlanConfirmService.normalizeImportance("4")).isEqualTo(4);
        assertThat(PlanConfirmService.normalizeImportance("4.6")).isEqualTo(5);
    }

    @Test
    void normalizeImportanceClampsOutOfRangeAndDefaultsUnknownValues() {
        assertThat(PlanConfirmService.normalizeImportance(0)).isEqualTo(1);
        assertThat(PlanConfirmService.normalizeImportance(9)).isEqualTo(5);
        assertThat(PlanConfirmService.normalizeImportance(null)).isEqualTo(3);
        assertThat(PlanConfirmService.normalizeImportance("")).isEqualTo(3);
        assertThat(PlanConfirmService.normalizeImportance("not sure")).isEqualTo(3);
    }
}
