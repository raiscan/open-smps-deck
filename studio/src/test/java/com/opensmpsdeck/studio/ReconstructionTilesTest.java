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
}
