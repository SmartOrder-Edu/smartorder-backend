package com.smartorder.edu.student.domain.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationQuestionTest {

    @Test
    void exposesScoreAsQuestionDefaultFullScore() throws NoSuchFieldException {
        Field score = EvaluationQuestion.class.getDeclaredField("score");

        assertEquals(BigDecimal.class, score.getType());
    }
}
