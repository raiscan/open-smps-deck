package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DacSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.opensmpsdeck.headless.DacAcoustics.SampleClass.NOISE_LIKE;
import static com.opensmpsdeck.headless.DacAcoustics.SampleClass.PITCHED_TONAL;
import static com.opensmpsdeck.headless.DacAcoustics.SampleClass.SPEECH_LIKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DacAcousticsTest {
    @Test void pitchAndEnvelopeMovementCannotMakeABriefHitSpeech() {
        assertNotEquals(SPEECH_LIKE, movingHarmonics(.075, 230, 470).sampleClass());
    }

    @Test void movingHarmonicsAloneCannotMakeASteadyToneSpeech() {
        assertEquals(PITCHED_TONAL, movingHarmonics(.18, 300, 300).sampleClass());
    }

    @Test void shortBrightHitWithASingleFundamentalRemainsEligibleForHats() {
        int rate = 20;
        double sampleHz = DacSample.sampleRateHz(rate);
        int period = (int) Math.round(sampleHz / 435);
        double[] repeatingNoise = new Random(54).doubles(period, -1, 1).toArray();
        var random = new Random(34);
        byte[] pcm = new byte[(int) (sampleHz * .075)];
        for (int i = 0; i < pcm.length; i++) {
            double phase = 2 * Math.PI * i / period;
            double progress = i / (double) pcm.length;
            // Change the harmonic balance over the hit while holding its fundamental fixed.
            double harmonics = .65 * ((1 - progress) * Math.sin(4 * phase) + progress * Math.sin(7 * phase));
            double wave = harmonics + .15 * Math.sin(phase) + .3 * (2 * random.nextDouble() - 1)
                    + .2 * repeatingNoise[i % period];
            pcm[i] = (byte) (128 + Math.round(95 * Math.exp(-2.15 * progress) * wave));
        }

        var profile = DacAcoustics.profile(DacSample.of("burst", "Bright hit", rate, pcm));
        assertEquals(NOISE_LIKE, profile.sampleClass());
    }

    @Test void listeningCurationOverridesAcousticsForTheVerifiedS3kSlots() {
        var builder = new InstrumentCatalogue.Builder();
        var recipe = new InstrumentCatalogue.Recipe(12, null, 127, 60, null, null, List.of(), List.of());
        for (int slot : new int[] {0xA5, 0xA9, 0xAA, 0xBE}) {
            byte[] pcm = new byte[2_500];
            new Random(slot).nextBytes(pcm);
            String asset = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Synthetic hit", pcm);
            builder.variant(asset, "Synthetic hit", recipe, new InstrumentCatalogue.Occurrence(
                    "fixture", "S3K", CatalogueMatcher.LOCKED_ON_S3K, null, null, slot, null, null, false, "dac"));
        }
        byte[] pcm = new byte[2_500];
        new Random(5).nextBytes(pcm);
        String otherRevision = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Other hit", pcm);
        builder.variant(otherRevision, "Other hit", recipe, new InstrumentCatalogue.Occurrence(
                "fixture", "S3K", "other revision", null, null, 0xA5, null, null, false, "dac"));

        var acoustics = new DacAcoustics(builder.build());
        var samples = acoustics.report().samples();
        assertEquals(5, samples.size());
        for (var sample : samples) {
            assertEquals(NOISE_LIKE, sample.nativeRates().getFirst().profile().sampleClass());
            if (sample.assetId().equals(otherRevision)) {
                assertEquals(NOISE_LIKE, sample.sampleClass());
                assertFalse(sample.excludeFromDrumFit());
                assertNull(sample.curationSource());
            } else {
                assertEquals(SPEECH_LIKE, sample.sampleClass());
                assertTrue(sample.excludeFromDrumFit());
                assertEquals("user listening 2026-11-22", sample.curationSource());
            }
        }
    }

    private static DacAcoustics.Profile movingHarmonics(double seconds, double firstHz, double lastHz) {
        int rate = 20;
        double sampleHz = DacSample.sampleRateHz(rate);
        byte[] pcm = new byte[(int) (sampleHz * seconds)];
        double phase = 0;
        for (int i = 0; i < pcm.length; i++) {
            double hz = firstHz + (lastHz - firstHz) * i / pcm.length;
            phase += 2 * Math.PI * hz / sampleHz;
            double blend = .5 + .45 * Math.sin(2 * Math.PI * 10 * i / sampleHz);
            double wave = .30 * Math.sin(phase)
                    + .55 * ((1 - blend) * Math.sin(3 * phase) + blend * Math.sin(7 * phase));
            pcm[i] = (byte) (128 + Math.round(120 * wave));
        }
        return DacAcoustics.profile(DacSample.of("harmonics", "Moving harmonics", rate, pcm));
    }
}
