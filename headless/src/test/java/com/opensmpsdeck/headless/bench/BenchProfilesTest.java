package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.InstrumentCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BenchProfilesTest {
    @TempDir Path directory;

    @Test void profileTransformsAreDeterministicAndPreserveExpectedTracksAndCounts() throws Exception {
        var truth = new BenchTimeline.Truth(List.of(channel("FM1", 50, 0), channel("FM2", 60, 1),
                channel("PSG1", 70, 2), channel("PSG3", 80, 3),
                new BenchTimeline.Channel("FM6-DAC", List.of(), List.of(new BenchTimeline.Hit(.2, "hash", 0x86))),
                new BenchTimeline.Channel("noise", List.of(new BenchTimeline.Note(.3, .4, 90, null, 2)), List.of())));
        var drums = new BenchDrums(new InstrumentCatalogue.Builder().build(), "test");
        for (var profile : BenchMidi.Profile.values()) {
            var events = BenchMidi.events(truth, drums, profile, 6, 1);
            assertEquals(events, BenchMidi.events(truth, drums, profile, 6, 1));
            assertEquals(82, events.size());
            assertEquals(2, events.stream().filter(BenchMidi.Event::drum).count());
            assertTrue(events.stream().anyMatch(e -> e.drum() && e.pitch() == 36));
            assertTrue(events.stream().anyMatch(e -> e.drum() && e.pitch() == 42));

            Path first = directory.resolve(profile + "-a.mid");
            Path second = directory.resolve(profile + "-b.mid");
            BenchMidi.write(events, profile, first);
            BenchMidi.write(events, profile, second);
            assertEquals(-1, Files.mismatch(first, second));
            var tracks = MidiSystem.getSequence(first.toFile()).getTracks();
            assertEquals(switch (profile) { case clean -> 9; case sunoLike -> 4; default -> 3; }, tracks.length);
            if (profile == BenchMidi.Profile.sunoLike) {
                var tempo = (javax.sound.midi.MetaMessage) tracks[0].get(0).getMessage();
                assertEquals(0x51, tempo.getType());
                assertArrayEquals(new byte[]{7, (byte) 0xa1, 0x20}, tempo.getData());
                int phase = BenchMidi.offsetFrames(1, 2, 360);
                assertEquals(phase, BenchMidi.offsetFrames(1, 2, 360));
                assertTrue(phase > 0 && phase < 120);
                assertEquals(events.getFirst().start() + phase / 60.0,
                        BenchMidi.shift(events, phase, 6).getFirst().start(), 1e-9);
            }
            int attacks = 0, drumAttacks = 0;
            for (var track : tracks) {
                for (int i = 0; i < track.size(); i++) {
                    if (track.get(i).getMessage() instanceof ShortMessage message
                            && message.getCommand() == ShortMessage.NOTE_ON && message.getData2() != 0) {
                        attacks++;
                        if (message.getChannel() == 9) drumAttacks++;
                    }
                }
            }
            assertEquals(82, attacks);
            assertEquals(2, drumAttacks);
        }

        var noisy = BenchMidi.events(truth, drums, BenchMidi.Profile.noisy, 6, 1).stream()
                .filter(e -> !e.drum()).toList();
        var originals = BenchMidi.events(truth, drums, BenchMidi.Profile.merged, 6, 1).stream()
                .filter(e -> !e.drum()).toList();
        assertEquals(4, noisy.stream().filter(event -> originals.stream().noneMatch(source ->
                event.pitch() == source.pitch() && Math.abs(event.start() - source.start()) <= .015)).count());
        assertEquals(4, originals.stream().filter(source -> noisy.stream().noneMatch(event ->
                event.pitch() == source.pitch() && Math.abs(event.start() - source.start()) <= .015)).count());
    }

    private static BenchTimeline.Channel channel(String name, int pitch, int phase) {
        return new BenchTimeline.Channel(name, IntStream.range(0, 20).mapToObj(i -> {
            double start = .05 + .05 * phase + i * .25;
            return new BenchTimeline.Note(start, start + .04, pitch, null, null);
        }).toList(), List.of());
    }

    @Test void audioVariantsAreDeterministicAndLengthPreserving() throws Exception {
        short[] source = new short[48_000];
        source[0] = 20_000;
        source[1] = 20_000;
        var pcm = new BenchAudio.Pcm(source, 48_000);
        for (var variant : BenchAudio.Variant.values()) {
            var audio = BenchAudio.degrade(pcm, variant);
            assertEquals(pcm.frames(), audio.frames());
            assertArrayEquals(audio.stereo(), BenchAudio.degrade(pcm, variant).stereo());
            Path file = directory.resolve(variant + ".wav");
            BenchAudio.write(audio, file);
            assertArrayEquals(audio.stereo(), BenchAudio.read(file).stereo());
            if (variant == BenchAudio.Variant.dry) assertArrayEquals(source, audio.stereo());
            else assertFalse(java.util.Arrays.equals(source, audio.stereo()));
        }
        var room = BenchAudio.degrade(pcm, BenchAudio.Variant.room).stereo();
        assertEquals(15_000, room[0]);
        assertTrue(room[2 * 48 * 29] > 0);
        assertTrue(room[2 * 48 * 58] > 0);
        var lofi = BenchAudio.degrade(pcm, BenchAudio.Variant.lofi).stereo();
        assertTrue(lofi[0] < 20_000);
        assertTrue(lofi[2] > 0);

        float[] dry = BenchAudio.mono(pcm);
        var identical = BenchAudio.compare(dry, dry);
        assertEquals(0, identical.logMel());
        assertEquals(1, identical.onsetCorrelation(), 1e-12);
        assertTrue(BenchAudio.compare(dry, BenchAudio.mono(BenchAudio.degrade(pcm, BenchAudio.Variant.room))).logMel() > 0);
    }
}
