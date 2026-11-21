package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KWeightedLevelTest {
    @Test void favorsBrightSignalsAtEqualRms() {
        double bass = tone(60, .1).power();
        double bright = tone(6000, .1).power();
        assertTrue(10 * Math.log10(bright / bass) > 5);
        // A duplicated 1 kHz tone at -20 dBFS peak measures about -20 LUFS.
        assertEquals(-20, -.691 + 10 * Math.log10(tone(1000, .1).power()), .05);
    }

    @Test void silenceAndQuietWindowsDoNotLowerActiveLevel() {
        var active = tone(1000, .1);
        double power = active.power();
        for (int i = 0; i < 5 * S3kRenderer.SAMPLE_RATE; i++) active.add(0, 0);
        assertEquals(power, active.power(), power * .04);
        assertEquals(0, tone(1000, 1e-5).power());
    }

    @Test void activeReferenceBlocksAlsoSelectTheRenderedAndBackingSamples() {
        var reference = new KWeightedLevel();
        var rendered = new KWeightedLevel();
        for (int frame = 0; frame < 5 * S3kRenderer.SAMPLE_RATE; frame++) {
            double tone = Math.sin(2 * Math.PI * 1000 * frame / S3kRenderer.SAMPLE_RATE);
            boolean active = frame < S3kRenderer.SAMPLE_RATE;
            reference.add(active ? tone * .1 : 0, active ? tone * .1 : 0);
            rendered.add(active ? tone * .05 : frame < 2 * S3kRenderer.SAMPLE_RATE ? 0 : tone * .5, active ? tone * .05 : frame < 2 * S3kRenderer.SAMPLE_RATE ? 0 : tone * .5);
        }
        var blocks = reference.activeBlocks();
        double ratio = rendered.powerOn(blocks) / reference.powerOn(blocks);
        assertEquals(.25, ratio, .01);
        assertTrue(rendered.power() > reference.power());
    }

    private static KWeightedLevel tone(int hz, double amplitude) {
        var meter = new KWeightedLevel();
        for (int frame = 0; frame < 5 * S3kRenderer.SAMPLE_RATE; frame++) {
            double sample = amplitude * Math.sin(2 * Math.PI * hz * frame / S3kRenderer.SAMPLE_RATE);
            meter.add(sample, sample);
        }
        return meter;
    }
}
