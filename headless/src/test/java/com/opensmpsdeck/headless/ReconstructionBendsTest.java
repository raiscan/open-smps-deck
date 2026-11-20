package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.FastYm2612Chip;
import com.openggf.audio.synth.fast.FastYm2612Dsp;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionBendsTest {
    private static final int PITCH = 77;
    private static final int DELAY = 4;
    private static final int FRAMES = 34;
    // Algorithm 7 with one sine carrier and its octave.
    private static final Voice VOICE = new Voice("voice", "Two carriers", List.of(
            7, 1, 2, 1, 1, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 0, 18, 127, 127));

    @Test void classifiesChipBendsAndRejectsNegativeContours() throws IOException {
        int detected = 0, encoded = 0;
        double squaredError = 0, acousticError = 0;
        var modes = java.util.EnumSet.noneOf(ReconstructionBendEncoding.Mode.class);
        for (int direction : new int[]{-1, 1}) {
            for (int cents : new int[]{100, 200, 700, 1200}) {
                for (int ticks : new int[]{3, 9, 24}) {
                    var audio = fixture(i -> direction * cents * Math.clamp((i - DELAY) / ticks, 0, 1));
                    var bend = ReconstructionBends.detect(audio, note());
                    if (bend == null) {
                        System.out.printf("Miss %d cents over %d frames%n", direction * cents, ticks);
                        continue;
                    }
                    detected++;
                    var fit = ReconstructionBendEncoding.fit(note().withBend(bend), 0, FRAMES, 0, VOICE);
                    assertNotNull(fit, direction * cents + " cents over " + ticks + " ticks");
                    encoded++;
                    modes.add(fit.mode());
                    squaredError += fit.rmseCents() * fit.rmseCents();
                    acousticError += Math.pow(roundTrip(fit, bend), 2);
                    assertEquals(direction, Integer.signum(bend.points().getLast().cents()));
                    assertEquals(direction * cents, bend.points().getLast().cents(), 55);
                    assertEquals(DELAY / 60.0, bend.delay(), .050);
                }
            }
        }
        var negatives = List.<DoubleUnaryOperator>of(i -> 0, i -> 95 * Math.sin(i * Math.TAU / 10),
                i -> i < 12 ? 0 : 1200, i -> i < 12 ? 0 : 500);
        int falsePositives = 0;
        for (var contour : negatives) {
            if (ReconstructionBends.detect(fixture(contour), note()) != null) falsePositives++;
        }
        var pad = fixture(i -> 0);
        var upper = fixture(i -> 330 + 70 * Math.sin(i * Math.TAU / 16));
        for (int i = 0; i < pad.samples().length; i++) pad.samples()[i] = .45f * (pad.samples()[i] + upper.samples()[i]);
        if (ReconstructionBends.detect(pad, note()) != null) falsePositives++;
        System.out.printf("Bends: %d/24 detected, %d/5 false positives%n", detected, falsePositives);
        System.out.printf("Encoded %d bends, register RMSE %.1f cents, PCM RMSE %.1f cents%n",
                encoded, Math.sqrt(squaredError / encoded), Math.sqrt(acousticError / encoded));
        assertTrue(Math.sqrt(acousticError / encoded) < 65);
        assertEquals(java.util.EnumSet.allOf(ReconstructionBendEncoding.Mode.class), modes);
        assertTrue(detected >= 22);
        assertEquals(0, falsePositives);
    }

    private static double roundTrip(ReconstructionBendEncoding.Fit fit, ReconstructionBends.Bend bend)
            throws IOException {
        var audio = render(fit.notes(), fit.commands());
        int frameSize = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        var fft = new LeadEffectMatcher.Spectrum();
        var window = new double[frameSize * 3 / 4];
        for (int i = 0; i < window.length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (window.length - 1));
        double squared = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            fft.transform(audio.samples(), frame * frameSize + (frameSize - window.length) / 2, window);
            double binHz = audio.sampleRate() / (double) fft.real.length;
            double expected = bend.cents((frame + .5) / 60.0);
            double targetHz = ReconstructionLeadAudio.hz(PITCH) * Math.pow(2, expected / 1200);
            int lowest = (int) Math.floor(.77 * targetHz / binHz);
            int highest = (int) Math.ceil(1.30 * targetHz / binHz);
            int best = lowest;
            for (int bin = lowest + 1; bin <= highest; bin++)
                if (fft.power(bin) > fft.power(best)) best = bin;
            assertTrue(best > lowest && best < highest, "No resolved fundamental in " + fit.mode());
            double a = Math.log(fft.power(best - 1)), b = Math.log(fft.power(best)), c = Math.log(fft.power(best + 1));
            double hz = (best + .5 * (a - c) / (a - 2 * b + c)) * binHz;
            squared += Math.pow(1200 * Math.log(hz / targetHz) / Math.log(2), 2);
        }
        return Math.sqrt(squared / FRAMES);
    }

    private static ReconstructionStages.Note note() {
        return new ReconstructionStages.Note("n", 0, 0, FRAMES / 60.0, PITCH, 96, "midi", 1);
    }

    private static ReferenceAudio fixture(DoubleUnaryOperator cents) {
        int rate = 24_000, samplesPerUpdate = 40;
        var chip = new FastYm2612Chip(new FastYm2612Dsp());
        chip.setOutputSampleRate(rate);
        chip.reset();
        chip.write(0, 0xb0, 7);
        chip.write(0, 0xb4, 0xc0);
        for (int slot = 0; slot < 4; slot++) {
            int offset = slot * 4;
            chip.write(0, 0x30 + offset, slot == 1 ? 2 : 1);
            chip.write(0, 0x40 + offset, slot == 0 ? 8 : slot == 1 ? 20 : 127);
            chip.write(0, 0x50 + offset, 31);
            chip.write(0, 0x80 + offset, 15);
        }
        int[] left = new int[samplesPerUpdate], right = new int[samplesPerUpdate];
        float[] mono = new float[FRAMES * rate / 60];
        for (int sample = 0; sample < mono.length; sample += samplesPerUpdate) {
            double pitch = PITCH + cents.applyAsDouble(sample * 60.0 / rate) / 100;
            int block = (int) (pitch / 12) - 1;
            double hz = 440 * Math.pow(2, (pitch - 69) / 12);
            int fnum = (int) Math.round(hz * 144 * Math.scalb(1, 21 - block) / 7_670_454);
            chip.write(0, 0xa4, (block << 3) | (fnum >> 8));
            chip.write(0, 0xa0, fnum & 255);
            if (sample == 0) chip.write(0, 0x28, 0xf0);
            Arrays.fill(left, 0);
            Arrays.fill(right, 0);
            chip.renderStereo(left, right);
            for (int i = 0; i < left.length; i++) mono[sample + i] = left[i] / 32768f;
        }
        return new ReferenceAudio(mono, rate);
    }

    private static ReferenceAudio render(List<NoteEvent> notes, List<CommandEvent> commands) throws IOException {
        var sequence = new Sequence("clip", "Chip fixture", "FM", FRAMES, VOICE.id(), 118, notes, commands, "RESET");
        var project = new StudioProject(4, "Bend fixture", "S3K", 30, 0, List.of(VOICE), List.of(sequence),
                List.of(new Clip("clip", sequence.id(), "FM1", 0, FRAMES)));
        var mono = new float[FRAMES * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION];
        var position = new int[1];
        var attacks = new int[1];
        var trace = new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port == 0 && register == 0x28 && (value & 0xf0) != 0) attacks[0]++;
            }

            @Override public void onPsgWrite(int value) {}
        };
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), FRAMES, pcm -> {
            for (int i = 0; i < pcm.length; i += 2) mono[position[0]++] = (pcm[i] + pcm[i + 1]) / 65536f;
        }, trace);
        assertEquals(1, attacks[0], "A bend must not retrigger the note");
        return new ReferenceAudio(mono, S3kRenderer.SAMPLE_RATE);
    }
}
