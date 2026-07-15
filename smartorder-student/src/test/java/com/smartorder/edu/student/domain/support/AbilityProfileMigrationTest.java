package com.smartorder.edu.student.domain.support;

import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityProfileMigrationTest {

    @Test
    void migratesLegacyNineAbilitiesIntoOnlySixRadarAbilities() {
        AbilityProfileDTO migrated = AbilityProfileMigration.migrate(legacyProfile());

        assertEquals(List.of(
                        "KNOWLEDGE_UNDERSTANDING",
                        "PROBLEM_ANALYSIS",
                        "SOLUTION_DESIGN",
                        "PRACTICAL_APPLICATION",
                        "ERROR_REFLECTION",
                        "TRANSFER_INNOVATION"
                ), List.copyOf(migrated.getAbilities().keySet()));
        assertEquals(52, migrated.getAbilities().get("PROBLEM_ANALYSIS").getScore());
        assertEquals(List.of("decomposition", "complexity"),
                migrated.getAbilities().get("PROBLEM_ANALYSIS").getEvidenceIds());
        assertFalse(migrated.getAbilities().containsKey("TECHNICAL_EXPRESSION"));
    }

    @Test
    void leavesAnAlreadyMigratedProfileUnchanged() {
        AbilityProfileDTO profile = new AbilityProfileDTO();
        profile.setAbilities(new LinkedHashMap<>());
        profile.getAbilities().put("KNOWLEDGE_UNDERSTANDING", ability(
                "KNOWLEDGE_UNDERSTANDING", "知识理解力", 70, "knowledge"));

        assertSame(profile, AbilityProfileMigration.migrate(profile));
    }

    private AbilityProfileDTO legacyProfile() {
        AbilityProfileDTO profile = new AbilityProfileDTO();
        LinkedHashMap<String, AbilityProfileDTO.AbilityItemDTO> abilities = new LinkedHashMap<>();
        abilities.put("CONCEPT_UNDERSTANDING", ability("CONCEPT_UNDERSTANDING", "概念理解", 76, "concept"));
        abilities.put("PROBLEM_DECOMPOSITION", ability("PROBLEM_DECOMPOSITION", "问题拆解", 68, "decomposition"));
        abilities.put("COMPLEXITY_ANALYSIS", ability("COMPLEXITY_ANALYSIS", "复杂度分析", 52, "complexity"));
        abilities.put("ABSTRACTION_MODELING", ability("ABSTRACTION_MODELING", "抽象建模", 62, "modeling"));
        abilities.put("ALGORITHM_DESIGN", ability("ALGORITHM_DESIGN", "算法设计", 58, "algorithm"));
        abilities.put("CODING_IMPLEMENTATION", ability("CODING_IMPLEMENTATION", "代码实现", 70, "coding"));
        abilities.put("DEBUGGING", ability("DEBUGGING", "调试分析", 55, "debugging"));
        abilities.put("KNOWLEDGE_TRANSFER", ability("KNOWLEDGE_TRANSFER", "知识迁移", 56, "transfer"));
        abilities.put("TECHNICAL_EXPRESSION", ability("TECHNICAL_EXPRESSION", "技术表达", 66, "expression"));
        profile.setAbilities(abilities);
        return profile;
    }

    private AbilityProfileDTO.AbilityItemDTO ability(String code, String name, int score, String evidenceId) {
        AbilityProfileDTO.AbilityItemDTO item = new AbilityProfileDTO.AbilityItemDTO();
        item.setAbilityCode(code);
        item.setAbilityName(name);
        item.setScore(score);
        item.setEvidenceIds(List.of(evidenceId));
        return item;
    }
}
