package com.opensmpsdeck.ui;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestPhraseColors {

    @Test
    void phraseColorIsDeterministic() {
        Color first = PhraseColors.forPhraseId(5);
        Color second = PhraseColors.forPhraseId(5);
        assertEquals(first, second, "Same phrase ID should always return the same color");
    }

    @Test
    void differentPhraseIdsCanDiffer() {
        Color color0 = PhraseColors.forPhraseId(0);
        Color color1 = PhraseColors.forPhraseId(1);
        assertNotEquals(color0, color1, "ID 0 and ID 1 should return different colors");
    }

    @Test
    void wrapsAround() {
        Color color0 = PhraseColors.forPhraseId(0);
        Color color12 = PhraseColors.forPhraseId(12);
        assertEquals(color0, color12, "ID 12 should wrap around to same color as ID 0 (12 colors in palette)");
    }

    @Test
    void negativePhraseIdDoesNotThrow() {
        Color result = PhraseColors.forPhraseId(-3);
        assertNotNull(result, "Negative phrase ID should return a non-null color");
    }
}
