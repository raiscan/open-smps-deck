package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection.AudioKind;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ReconstructionTilesTest {
    @Test void tilesKeepAtLeastOneMeasuredColumnPerPixel() {
        for (double pixelsPerSecond : new double[]{1, 6, 40, 128, 400, 3000}) {
            int level = ReconstructionTiles.level(pixelsPerSecond);
            double tilePixels = ReconstructionTiles.span(level) * pixelsPerSecond;
            if (level > ReconstructionTiles.MIN_LEVEL) assertTrue(tilePixels <= ReconstructionTiles.WIDTH, "level " + level);
            if (level < ReconstructionTiles.MAX_LEVEL) assertTrue(tilePixels > ReconstructionTiles.WIDTH / 2.0, "level " + level);
        }
        assertEquals(ReconstructionTiles.MIN_LEVEL, ReconstructionTiles.level(1e6));
        assertEquals(ReconstructionTiles.MAX_LEVEL, ReconstructionTiles.level(1e-3));
    }

    @Test void pitchBlocksAreFixedOctavesSoPitchScrollReusesTiles() {
        var source = new AudioSource(AudioKind.CHIP, "Chip render", Path.of("render.wav"), true);
        assertEquals(ReconstructionTiles.block(48), ReconstructionTiles.block(59));
        assertNotEquals(ReconstructionTiles.block(59), ReconstructionTiles.block(60));
        var key = ReconstructionTiles.key(source, 0, 3, ReconstructionTiles.block(64));
        assertEquals(60, key.low());
        assertEquals(71, key.high());
        assertEquals(4.5, key.start());
        assertEquals(6, key.end());
        // The top block stops at the last MIDI pitch.
        assertEquals(127, ReconstructionTiles.key(source, 0, 0, ReconstructionTiles.block(127)).high());
        assertEquals(0, ReconstructionTiles.block(-4));
        assertEquals(key, ReconstructionTiles.key(source, 0, 3, 5));
    }

    @Test void rowDensityFollowsPitchZoomInPowersOfTwo() {
        assertEquals(2, ReconstructionTiles.rows(.5));
        assertEquals(2, ReconstructionTiles.rows(2));
        assertEquals(4, ReconstructionTiles.rows(2.1));
        assertEquals(16, ReconstructionTiles.rows(15));
        assertEquals(ReconstructionTiles.MAX_ROWS, ReconstructionTiles.rows(80));
        var source = new AudioSource(AudioKind.CHIP, "Chip render", Path.of("render.wav"), true);
        // Row density and mode are part of the measurement, so tiles never mix.
        assertNotEquals(ReconstructionTiles.key(source, 0, 3, 5, 4, false), ReconstructionTiles.key(source, 0, 3, 5, 8, false));
        assertNotEquals(ReconstructionTiles.key(source, 0, 3, 5, 4, false), ReconstructionTiles.key(source, 0, 3, 5, 4, true));
    }

    @Test void colourScaleRunsDarkToBrightAcrossTheContrastWindow() {
        var contrast = new ReconstructionTiles.Contrast(-10, 40);
        assertEquals(-50, contrast.floor());
        double previous = -1;
        for (int db = -60; db <= 0; db += 2) {
            double lightness = lightness(ReconstructionTiles.color(db, contrast));
            assertTrue(lightness >= previous - 1e-9, "lightness rises at " + db + " dB");
            previous = lightness;
        }
        assertEquals(ReconstructionTiles.color(-50, contrast), ReconstructionTiles.color(-90, contrast));
        assertEquals(ReconstructionTiles.color(-10, contrast), ReconstructionTiles.color(0, contrast));
        assertTrue(lightness(ReconstructionTiles.color(-50, contrast)) < .05);
        assertTrue(lightness(ReconstructionTiles.color(-10, contrast)) > .9);
    }

    @Test void autoContrastFitsQuietAudio() {
        // A quiet stem: background near -95 dB, partials up to about -38 dB.
        var levels = new float[10_000];
        var random = new java.util.Random(7);
        for (int i = 0; i < levels.length; i++)
            levels[i] = i % 50 == 0 ? (float) (-38 - 4 * random.nextDouble()) : (float) (-95 + 6 * random.nextGaussian());
        levels[3] = Float.NEGATIVE_INFINITY;
        var contrast = ReconstructionTiles.autoContrast(java.util.List.of(levels));
        assertNotNull(contrast);
        assertTrue(contrast.ceiling() >= -42 && contrast.ceiling() <= -36, contrast.toString());
        assertTrue(contrast.floor() >= -100 && contrast.floor() <= -90, contrast.toString());
        assertNull(ReconstructionTiles.autoContrast(java.util.List.of()));
        // Extremes clamp to the slider range.
        var flat = new float[1000];
        java.util.Arrays.fill(flat, -20);
        assertEquals(20, ReconstructionTiles.autoContrast(java.util.List.of(flat)).range());
    }

    private static double lightness(int argb) {
        double r = (argb >> 16 & 255) / 255.0, g = (argb >> 8 & 255) / 255.0, b = (argb & 255) / 255.0;
        return .2126 * r + .7152 * g + .0722 * b;
    }
}
