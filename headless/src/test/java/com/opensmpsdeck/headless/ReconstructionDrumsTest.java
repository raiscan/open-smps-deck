package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconstructionDrumsTest {
    @TempDir Path directory;
    @Test void tonalEnvelopeCannotOpenTheNoiseChannelInADrumSolo() throws Exception {
        byte[] raw = {0x40, (byte) 0x80};
        var mask = new S3kRenderer.ChannelMask(0x3f, 0x7);
        assertFalse(noiseIsSilent(List.of(0x40, 0x80), mask), "the fixture must reproduce the leakage");
        assertTrue(noiseIsSilent(S3kReconstruction.psgAttenuation(raw), mask));
    }

    @Test void attenuationLimitPreservesLoopOffsetAndMultiplierOperand() {
        assertEquals(List.of(15, 0x82, 31, 0x84, 64, 15, 0x80),
                S3kReconstruction.psgAttenuation(new byte[] {64, (byte) 0x82, 31, (byte) 0x84, 64, 31, (byte) 0x80}));
    }

    @Test void stagedRunUsesTheFittedRateOrAnEditableStemSample() throws Exception {
        int nativeRate = 12;
        double pitch = 90;
        byte[] pcm = new byte[2_000];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (byte) (128 + Math.round(110 * kick(i / DacSample.sampleRateHz(nativeRate), pitch)));
        var builder = new InstrumentCatalogue.Builder();
        String sample = builder.asset(InstrumentCatalogue.Kind.DAC_SAMPLE, "Kick", pcm);
        builder.variant(sample, "Kick", new InstrumentCatalogue.Recipe(nativeRate, null, 127, 60,
                null, null, List.of(), List.of()), null);
        Path index = directory.resolve("catalogue.json");
        builder.build().save(index);

        int[] starts = {9_600, 24_000, 38_400};
        Path stem = directory.resolve("drums.wav");
        WaveFile.write(stem, 72, sink -> {
            short[] frame = new short[1_600];
            for (int f = 0; f < 72; f++) {
                for (int i = 0; i < 800; i++) {
                    double value = 0;
                    for (int attack : starts) {
                        int elapsed = f * 800 + i - attack;
                        if (elapsed >= 0 && elapsed < 9_000) value += kick(elapsed / 48_000.0, 113);
                    }
                    frame[2 * i] = frame[2 * i + 1] = (short) Math.round(12_000 * value);
                }
                sink.accept(frame);
            }
        });
        Path midi = directory.resolve("drums.mid");
        var sequence = new javax.sound.midi.Sequence(javax.sound.midi.Sequence.PPQ, 480);
        var track = sequence.createTrack();
        for (int start : starts) {
            int tick = start / 50;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 36, 100), tick));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, 36, 0), tick + 12));
        }
        MidiSystem.write(sequence, 0, midi.toFile());

        var catalogue = reconstruct(midi, stem, index, "catalogue", true);
        assertTrue(catalogue.id().startsWith(sample));
        assertTrue(catalogue.rate() < nativeRate, "the higher reference pitch should choose faster DAC playback");
        assertTrue(reconstruct(midi, stem, index, "sliced", true).id().startsWith("stem-"));
        assertTrue(reconstruct(midi, stem, index, "auto", false).id().startsWith(sample));
    }

    private static double kick(double seconds, double pitch) {
        return Math.sin(2 * Math.PI * pitch * seconds) * Math.exp(-25 * seconds);
    }

    private DacSample reconstruct(Path midi, Path stem, Path index, String mode, boolean withStem) throws Exception {
        Path work = directory.resolve(mode);
        var args = new java.util.ArrayList<>(List.of("reconstruct", "run", midi.toString(), stem.toString(),
                index.toString(), work.toString(), "--steady-tempo", "off", "--drum-source", mode));
        if (withStem) args.addAll(List.of("--stem", "0=" + stem));
        var stderr = new ByteArrayOutputStream();
        try (var diagnostics = new PrintStream(stderr)) {
            assertEquals(0, Main.run(args.toArray(String[]::new), diagnostics, diagnostics), stderr.toString());
        }
        var report = JsonParser.parseString(Files.readString(work.resolve("report.json"))).getAsJsonObject();
        assertEquals(mode, report.get("drumSource").getAsString());
        assertEquals(3, report.get("dacHits").getAsInt());
        return ProjectFiles.load(work.resolve("arrangement.smps.json")).project().samples().getFirst();
    }

    private static boolean noiseIsSilent(List<Integer> values, S3kRenderer.ChannelMask mask) throws Exception {
        var envelope = new Envelope("env", "Volume", Envelope.PSG_VOLUME, values);
        var tone = new Sequence("tone", "Tone", "PSG", 16, null, 95,
                List.of(new NoteEvent("note", 0, 16, 69)), List.of(), "RESET", envelope.id(), null);
        var project = new StudioProject(4, "Envelope isolation", "S3K", 30, 0, List.of(), List.of(tone),
                List.of(new Clip("tone-clip", tone.id(), "PSG2", 0, 16)), List.of(envelope));
        // Compare each presentation's settled waveform against the constant
        // hardware resting level rather than assuming silence is digital zero.
        boolean[] silent = {true};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), 12, mask, pcm -> {
            for (int i = 64; i < pcm.length; i++) {
                if (pcm[i] != pcm[64]) silent[0] = false;
            }
        });
        return silent[0];
    }
}
