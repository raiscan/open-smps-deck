package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class AudioFingerprintTest {
    private static final int RATE = 48_000;

    @Test void gainDcAndModerateLeadingSilencePreserveTheFingerprint() {
        var audio = signal(0.6, t -> Math.sin(2 * Math.PI * 220 * t) * Math.exp(-4 * t));
        var moved = new float[audio.length + 2293];
        Arrays.fill(moved, 0.12f);
        for (int i = 0; i < audio.length; i++) moved[i + 2293] += 0.16f * audio[i];

        var a = AudioFingerprint.extract(audio, RATE);
        var b = AudioFingerprint.extract(moved, RATE);
        assertEquals(0, a.distance(a).total(), 1e-12);
        assertEquals(a.distance(b), b.distance(a));
        assertTrue(a.distance(b).total() < 1e-5, a.distance(b)::toString);
    }

    @Test void distinguishesDecayTimingEvenWhenPitchAndBrightnessMatch() {
        var shortTone = tone(18);
        var longTone = tone(2);
        var difference = shortTone.distance(longTone);
        assertTrue(longTone.summary().decaySeconds() > shortTone.summary().decaySeconds() + 0.25);
        assertTrue(difference.envelope() > 4 * difference.spectral(), difference::toString);
    }

    @Test void brightnessAndNoisinessSeparateSamePitchTimbralChanges() {
        Random random = new Random(208);
        var pure = tone(4);
        var brighter = AudioFingerprint.extract(signal(0.6, t ->
                (Math.sin(2 * Math.PI * 220 * t) + Math.sin(2 * Math.PI * 880 * t)) * Math.exp(-4 * t)), RATE);
        var noise = AudioFingerprint.extract(signal(0.6, t ->
                (2 * random.nextDouble() - 1) * Math.exp(-4 * t)), RATE);

        assertTrue(brighter.summary().centroidHz() > pure.summary().centroidHz());
        assertTrue(noise.summary().flatness() > pure.summary().flatness());
        assertTrue(noise.summary().noisiness() > pure.summary().noisiness());
        assertTrue(pure.distance(brighter).total() < pure.distance(noise).total());
    }

    @Test void shortClicksWorkAndUnusableAudioFails() {
        var click = new float[RATE / 100];
        click[3] = 0.9f;
        var analyzed = AudioFingerprint.extract(click, RATE);
        assertTrue(Double.isFinite(analyzed.summary().centroidHz()));

        assertThrows(IllegalArgumentException.class, () -> AudioFingerprint.extract(new float[RATE], RATE));
        float[] constant = new float[RATE];
        Arrays.fill(constant, 0.3f);
        assertThrows(IllegalArgumentException.class, () -> AudioFingerprint.extract(constant, RATE));
        click[7] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> AudioFingerprint.extract(click, RATE));
        assertThrows(IllegalArgumentException.class, () -> AudioFingerprint.extract(click, 1000));
        assertThrows(IllegalArgumentException.class, () -> AudioFingerprint.extract(new float[3 * RATE + 1], RATE));
    }

    private static AudioFingerprint tone(double decay) {
        return AudioFingerprint.extract(signal(0.6, t -> Math.sin(2 * Math.PI * 220 * t) * Math.exp(-decay * t)), RATE);
    }

    private static float[] signal(double seconds, DoubleUnaryOperator synthesis) {
        float[] signal = new float[(int) (seconds * RATE)];
        for (int i = 0; i < signal.length; i++) signal[i] = (float) synthesis.applyAsDouble((double) i / RATE);
        return signal;
    }
}
