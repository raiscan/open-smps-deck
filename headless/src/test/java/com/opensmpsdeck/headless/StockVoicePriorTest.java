package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StockVoicePriorTest {
    @Test void coversCommunityNamesAndStemLabels() {
        assertEquals(StockVoicePrior.Family.keyboard, StockVoicePrior.stemFamily("electric_piano"));
        assertEquals(StockVoicePrior.Family.lead, StockVoicePrior.stemFamily("backing vocals"));
        assertNull(StockVoicePrior.stemFamily("bassoon"));
        assertTrue(StockVoicePrior.nameFamilies("Bell Piano").contains(StockVoicePrior.Family.keyboard));
        assertTrue(StockVoicePrior.nameFamilies("Picked Bass").contains(StockVoicePrior.Family.guitar));
        assertTrue(StockNames.UNIVERSAL_VOICES.stream().allMatch(name -> !StockVoicePrior.nameFamilies(name).isEmpty()));
    }

    @Test void summarizesRegisterDurationAndChordUse() {
        var voice = List.of(new RetailFmUsage.Gate("FM3", 64, 0, 18),
                new RetailFmUsage.Gate("FM3", 66, 24, 39));
        var song = List.of(voice.getFirst(), voice.getLast(),
                new RetailFmUsage.Gate("FM2", 57, 0, 18));
        var usage = RetailFmUsage.summarize(voice, song);
        assertEquals(new InstrumentCatalogue.FmUsage(2, 64, 66, 66, 18, "chord"), usage);
    }

    @Test void preferenceSettlesOnlyExactAcousticTies() {
        var original = new VoiceFit.Candidate("plain", new float[]{1, 1, 1, 1});
        var fit = new VoiceFit(new float[]{1, .9f, 1, .9f}, 2, List.of(original), Set.of());
        // A preferred patch that fits slightly worse does not displace the incumbent.
        var preferred = new VoiceFit.Candidate("preferred", new float[]{1, 1.001f, 1, 1.001f}, .04);
        assertFalse(fit.select(0, List.of(preferred), 0));
        assertEquals("plain", fit.selection(0).candidate().id());
        // An identical spectrum with a preference wins the tie.
        var named = new VoiceFit.Candidate("named", new float[]{1, 1, 1, 1}, .04);
        assertTrue(fit.select(0, List.of(named), 0));
        assertEquals("named", fit.selection(0).candidate().id());
        // A better acoustic fit wins regardless of preference.
        var accurate = new VoiceFit.Candidate("accurate", new float[]{1, .9f, 1, .9f});
        assertTrue(fit.select(0, List.of(accurate), 0));
        assertEquals("accurate", fit.selection(0).candidate().id());
    }

    @Test void nearSilentAndOneShotPatchesFailAutomaticEligibility() {
        assertFalse(VoicePatchShortlist.audible(0, 0, false));
        assertFalse(VoicePatchShortlist.audible(.003, 0, true));
        assertTrue(VoicePatchShortlist.audible(.003, 0, false));
        assertTrue(VoicePatchShortlist.audible(.003, .0002, true));
    }
}
