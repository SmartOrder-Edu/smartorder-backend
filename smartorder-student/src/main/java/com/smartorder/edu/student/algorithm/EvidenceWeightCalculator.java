package com.smartorder.edu.student.algorithm;

import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class EvidenceWeightCalculator {

    public BigDecimal calculate(EvaluationAnswerRecord record, EvaluationQuestion question) {
        double typeWeight = typeWeight(question.getQuestionType());
        int hintCount = record.getUsedHint() != null ? record.getUsedHint() : 0;
        double hintPenalty = 1.0 / (1.0 + 0.25 * hintCount);
        double timeFactor = timeFactor(record.getDuration(), expectedSeconds(question));
        double weight = typeWeight * hintPenalty * timeFactor;
        return BigDecimal.valueOf(clamp(weight, 0.05, 1.40)).setScale(4, RoundingMode.HALF_UP);
    }

    public Integer expectedSeconds(EvaluationQuestion question) {
        int difficulty = question.getDifficulty() != null ? question.getDifficulty() : 1;
        return switch (question.getQuestionType()) {
            case "code" -> 900 + difficulty * 180;
            case "short_answer", "scenario", "sql", "multimodal" -> 240 + difficulty * 90;
            case "fill_blank" -> 90 + difficulty * 30;
            default -> 60 + difficulty * 20;
        };
    }

    private double typeWeight(String questionType) {
        return switch (questionType) {
            case "fill_blank" -> 0.90;
            case "short_answer", "scenario", "sql", "multimodal" -> 1.00;
            case "code" -> 1.20;
            default -> 0.80;
        };
    }

    private double timeFactor(Integer actualSeconds, Integer expectedSeconds) {
        if (actualSeconds == null || actualSeconds <= 0 || expectedSeconds == null || expectedSeconds <= 0) {
            return 1.0;
        }
        return clamp((double) expectedSeconds / actualSeconds, 0.60, 1.20);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
