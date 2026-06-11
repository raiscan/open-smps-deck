package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestGmVoiceSuggestions {

    @Test
    void everyGmProgramReturnsAValidVoice() {
        for (int prog = 0; prog < 128; prog++) {
            FmVoice v = GmVoiceSuggestions.forProgram(prog);
            assertNotNull(v);
            assertEquals(FmVoice.VOICE_SIZE, v.getData().length);
        }
    }

    @Test
    void leadProgramsGetTheSquareLead() {
        assertEquals("GM Square Lead", GmVoiceSuggestions.forProgram(80).getName());
    }

    @Test
    void bassProgramsGetTheBass() {
        assertEquals("GM FM Bass", GmVoiceSuggestions.forProgram(32).getName());
    }

    @Test
    void seedBankHasDistinctVoices() {
        var bank = GmVoiceSuggestions.seedBank();
        assertTrue(bank.size() >= 4);
        long distinct = bank.stream().map(FmVoice::getName).distinct().count();
        assertEquals(bank.size(), distinct);
    }
}
