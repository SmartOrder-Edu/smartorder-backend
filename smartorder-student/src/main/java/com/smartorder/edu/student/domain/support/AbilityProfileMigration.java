package com.smartorder.edu.student.domain.support;

import com.smartorder.edu.student.domain.dto.student.AbilityProfileDTO;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the former programming-oriented ability profile into the six
 * cross-task abilities used by the comprehensive radar chart.
 */
public final class AbilityProfileMigration {

    private static final Map<String, String> ABILITY_NAMES = Map.of(
            "KNOWLEDGE_UNDERSTANDING", "知识理解力",
            "PROBLEM_ANALYSIS", "问题分析力",
            "SOLUTION_DESIGN", "方案设计力",
            "PRACTICAL_APPLICATION", "实践应用力",
            "ERROR_REFLECTION", "纠错反思力",
            "TRANSFER_INNOVATION", "迁移创新力"
    );
    private static final List<String> SIX_DIMENSION_CODES = List.of(
            "KNOWLEDGE_UNDERSTANDING",
            "PROBLEM_ANALYSIS",
            "SOLUTION_DESIGN",
            "PRACTICAL_APPLICATION",
            "ERROR_REFLECTION",
            "TRANSFER_INNOVATION"
    );
    private static final Set<String> LEGACY_CODES = Set.of(
            "CONCEPT_UNDERSTANDING",
            "ABSTRACTION_MODELING",
            "PROBLEM_DECOMPOSITION",
            "ALGORITHM_DESIGN",
            "COMPLEXITY_ANALYSIS",
            "CODING_IMPLEMENTATION",
            "DEBUGGING",
            "KNOWLEDGE_TRANSFER",
            "TECHNICAL_EXPRESSION"
    );

    private AbilityProfileMigration() {
    }

    public static AbilityProfileDTO migrate(AbilityProfileDTO profile) {
        if (!requiresMigration(profile)) {
            return profile;
        }

        Map<String, AbilityProfileDTO.AbilityItemDTO> oldAbilities = profile.getAbilities();
        Map<String, AbilityProfileDTO.AbilityItemDTO> migrated = new LinkedHashMap<>();
        migrate(migrated, oldAbilities, "KNOWLEDGE_UNDERSTANDING", "CONCEPT_UNDERSTANDING");
        migrate(migrated, oldAbilities, "PROBLEM_ANALYSIS", "PROBLEM_DECOMPOSITION", "COMPLEXITY_ANALYSIS");
        migrate(migrated, oldAbilities, "SOLUTION_DESIGN", "ABSTRACTION_MODELING", "ALGORITHM_DESIGN");
        migrate(migrated, oldAbilities, "PRACTICAL_APPLICATION", "CODING_IMPLEMENTATION");
        migrate(migrated, oldAbilities, "ERROR_REFLECTION", "DEBUGGING");
        migrate(migrated, oldAbilities, "TRANSFER_INNOVATION", "KNOWLEDGE_TRANSFER");
        SIX_DIMENSION_CODES.stream()
                .filter(oldAbilities::containsKey)
                .forEach(code -> migrated.putIfAbsent(code, oldAbilities.get(code)));

        profile.setAbilities(migrated);
        recalculateDerivedFields(profile);
        return profile;
    }

    public static boolean isSixDimensionCode(String code) {
        return SIX_DIMENSION_CODES.contains(code);
    }

    public static boolean requiresMigration(AbilityProfileDTO profile) {
        return profile != null && profile.getAbilities() != null && !profile.getAbilities().isEmpty()
                && profile.getAbilities().keySet().stream().anyMatch(LEGACY_CODES::contains);
    }

    private static void migrate(
            Map<String, AbilityProfileDTO.AbilityItemDTO> target,
            Map<String, AbilityProfileDTO.AbilityItemDTO> source,
            String targetCode,
            String... sourceCodes
    ) {
        List<AbilityProfileDTO.AbilityItemDTO> candidates = new ArrayList<>();
        for (String sourceCode : sourceCodes) {
            AbilityProfileDTO.AbilityItemDTO item = source.get(sourceCode);
            if (item != null) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        AbilityProfileDTO.AbilityItemDTO representative = candidates.stream()
                .min(Comparator.comparing(AbilityProfileMigration::scoreOrMax))
                .orElseThrow();
        AbilityProfileDTO.AbilityItemDTO migrated = copy(representative);
        migrated.setAbilityCode(targetCode);
        migrated.setAbilityName(ABILITY_NAMES.get(targetCode));
        migrated.setEvidenceIds(candidates.stream()
                .filter(item -> item.getEvidenceIds() != null)
                .flatMap(item -> item.getEvidenceIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList());
        target.put(targetCode, migrated);
    }

    private static AbilityProfileDTO.AbilityItemDTO copy(AbilityProfileDTO.AbilityItemDTO source) {
        AbilityProfileDTO.AbilityItemDTO copy = new AbilityProfileDTO.AbilityItemDTO();
        copy.setScore(source.getScore());
        copy.setLevel(source.getLevel());
        copy.setTrend(source.getTrend());
        copy.setStrengthSummary(source.getStrengthSummary());
        copy.setImprovementFocus(source.getImprovementFocus());
        copy.setConfidence(source.getConfidence());
        copy.setLastEvaluatedAt(source.getLastEvaluatedAt());
        return copy;
    }

    private static void recalculateDerivedFields(AbilityProfileDTO profile) {
        List<Map.Entry<String, AbilityProfileDTO.AbilityItemDTO>> scored = profile.getAbilities().entrySet().stream()
                .filter(entry -> entry.getValue().getScore() != null)
                .toList();
        if (!scored.isEmpty()) {
            double average = scored.stream().mapToInt(entry -> entry.getValue().getScore()).average().orElseThrow();
            profile.setOverallScore(BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP).doubleValue());
            profile.setOverallLevel(levelFor(average));
        }
        profile.setStrengthAbilityCodes(scored.stream()
                .sorted(Map.Entry.<String, AbilityProfileDTO.AbilityItemDTO>comparingByValue(
                        Comparator.comparing(AbilityProfileMigration::scoreOrZero)).reversed())
                .limit(2)
                .map(Map.Entry::getKey)
                .toList());
        profile.setDevelopmentPriorityCodes(scored.stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(AbilityProfileMigration::scoreOrZero)))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList());

        DimensionMetadataDTO metadata = profile.getMetadata();
        if (metadata != null) {
            metadata.setSchemaVersion("2.0");
            metadata.setCompleteness((int) Math.round(profile.getAbilities().size() * 100.0 / SIX_DIMENSION_CODES.size()));
        }
    }

    private static String levelFor(double score) {
        if (score < 60) {
            return "BEGINNER";
        }
        if (score < 75) {
            return "DEVELOPING";
        }
        if (score < 90) {
            return "PROFICIENT";
        }
        return "ADVANCED";
    }

    private static int scoreOrMax(AbilityProfileDTO.AbilityItemDTO item) {
        return item.getScore() == null ? Integer.MAX_VALUE : item.getScore();
    }

    private static int scoreOrZero(AbilityProfileDTO.AbilityItemDTO item) {
        return item.getScore() == null ? 0 : item.getScore();
    }
}
