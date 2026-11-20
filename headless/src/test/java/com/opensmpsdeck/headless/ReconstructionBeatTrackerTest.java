package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionBeatTrackerTest {
    private static final double BPM = 172;
    private static final double OFFSET = .531;
    @TempDir Path temp;

    @Test void tracksAccelerandoAndWarpsBothEndsOfNotesWithoutChangingSourceTimes() throws Exception {
        double duration = 150, change = 13;
        var expected = new ArrayList<Double>();
        var midi = writeMidi(duration, change, expected);
        var map = ReconstructionBeatTracker.track(midi, duration, List.of(), 4);
        assertNotNull(map);
        assertEquals(BPM, map.openingBpm(), .6);
        assertEquals(BPM + change, map.closingBpm(), .6);
        assertTrue(map.confidence() >= ReconstructionTimeMap.MIN_CONFIDENCE);
        for (int i = 0; i < expected.size(); i++) {
            double time = expected.get(i);
            int beat = (int) Math.round(map.index(time));
            assertEquals(time, map.seconds(beat), .020, "beat " + i);
            assertEquals(i % 4 == 0, Math.floorMod(beat - map.downbeat(), 4) == 0);
        }
        var score = score(midi, duration, map, null, null);
        var timeline = ReconstructionTimeMap.of(score);
        assertTrue(timeline.warped());
        assertTrue(timeline.frames() > score.frames());
        assertEquals(42.5, timeline.source(timeline.project(42.5)), 1e-10);
        assertEquals(0, timeline.project(0), 1e-10);

        int melody = midi.parts().stream().filter(part -> !part.drums()).findFirst().orElseThrow().id();
        var notes = new ArrayList<ReconstructionStages.Note>();
        for (int i = 0; i < expected.size() - 1; i++) {
            var start = expected.get(i);
            notes.add(new ReconstructionStages.Note("n" + i, melody, start, time(i + .5, duration, change),
                    72, 96, "midi", 1));
        }
        var roles = new ReconstructionRoles.Result(1, 0, notes.stream().map(note ->
                new ReconstructionRoles.Decision(note, ReconstructionRoles.Role.melody, null, null)).toList());
        var result = ReconstructionAllocation.allocate(roles, midi.parts(), score.frames(), false, timeline);
        double origin = timeline.project(expected.getFirst());
        double period = 60 / timeline.bpm();
        for (int i = 0; i < notes.size(); i++) {
            var placement = result.notes().get(i);
            assertEquals(notes.get(i), placement.note());
            assertEquals(Math.round((origin + i * period) * 60), placement.startTick(), 1);
            assertEquals(Math.round((origin + (i + .5) * period) * 60), placement.endTick(), 1);
        }
        var fit = ReconstructionAllocation.sourcePlacement(result, score.frames(), timeline);
        assertEquals(notes.getLast().startTick(score.frames()), fit.notes().getLast().startTick());
        assertFalse(ReconstructionTimeMap.of(score(midi, duration, map, null, "off")).warped());
    }

    @Test void followsMoreThanTenPercentDrift() throws Exception {
        double duration = 90, change = -20;
        var expected = new ArrayList<Double>();
        var map = ReconstructionBeatTracker.track(writeMidi(duration, change, expected), duration, List.of(), 4);
        assertNotNull(map);
        assertEquals(BPM + change, map.closingBpm(), .8);
        for (double time : expected) assertEquals(time, map.seconds(Math.round(map.index(time))), .020);
    }

    @Test void recoversAFrameClockFromDrumOnsets() throws Exception {
        double duration = 30 / 1.035;
        double slope = .07 / (2 * duration);
        var sequence = new Sequence(Sequence.PPQ, 5_000);
        var drums = sequence.createTrack();
        for (int i = 0; i * .09 + .2 < 30; i++) {
            double clock = Math.round((i * .09 + .2) * 60) / 60.0;
            double source = 2 * clock / (1 + Math.sqrt(1 + 4 * slope * clock));
            hit(drums, 9, switch (i % 8) { case 0 -> 36; case 4 -> 38; default -> 42; }, source);
        }

        var file = temp.resolve("frame-clock.mid");
        MidiSystem.write(sequence, 0, file.toFile());
        var midi = MidiScore.read(file);
        var beats = ReconstructionBeatTracker.track(midi, duration, List.of(), 4);
        assertNotNull(beats);
        var timeline = ReconstructionTimeMap.of(score(midi, duration, beats, null, "auto"));
        assertTrue(timeline.warped());
        for (double time = 0; time < duration; time += .25)
            assertEquals(time * (1 + slope * time), timeline.project(time), .001);
    }

    @Test void stableInputAndManualGridKeepSourceTimeUnlessExplicitlySteadied() throws Exception {
        var midi = writeMidi(30, 0, new ArrayList<>());
        var map = ReconstructionBeatTracker.track(midi, 30, List.of(), 4);
        assertNotNull(map);
        assertEquals(BPM, map.openingBpm(), .1);
        assertFalse(ReconstructionTimeMap.of(score(midi, 30, map, null, "auto")).warped());
        assertFalse(ReconstructionTimeMap.of(score(midi, 30, map, BPM, null)).warped());
        var timeline = ReconstructionTimeMap.of(score(midi, 30, map, BPM, "86"));
        assertTrue(timeline.warped());
        assertEquals(40, timeline.project(20), .025);
    }

    private static ReconstructionStages.Score score(MidiScore midi, double duration, ReconstructionBeatMap map,
                                                     Double bpm, String steady) {
        var inputs = new ReconstructionStages.Inputs("test.mid", "test.wav", "catalogue.json", "Test", 21,
                false, false, false, false, Map.of(), null, 0,
                new ReconstructionTempo.Options(false, bpm, null, steady), false);
        return new ReconstructionStages.Score(1, Math.round(duration * 1e6), (int) (duration * 60), 60, inputs,
                map.bars(duration), midi, Map.of(), List.of(), List.of(), List.of(), null, null, map);
    }

    private MidiScore writeMidi(double duration, double change, List<Double> expected) throws Exception {
        var sequence = new Sequence(Sequence.PPQ, 5_000);
        var melody = sequence.createTrack();
        var drums = sequence.createTrack();
        for (int i = 0; time(i + .7, duration, change) < duration; i++) {
            double time = time(i, duration, change);
            expected.add(time);
            hit(drums, 9, i % 2 == 0 ? 36 : 38, time);
            if (i % 4 == 0) hit(drums, 9, 49, time);
            hit(drums, 9, 42, time(i + .5, duration, change));
            hit(melody, 0, 72 + i % 4, time + .004 * Math.sin(i));
            hit(melody, 0, 64, time(i + .75, duration, change));
        }
        var file = temp.resolve("fixture.mid");
        MidiSystem.write(sequence, 1, file.toFile());
        return MidiScore.read(file);
    }

    private static double time(double beat, double duration, double change) {
        double distance = 60 * beat;
        return OFFSET + 2 * distance / (BPM + Math.sqrt(BPM * BPM + 2 * change * distance / duration));
    }

    private static void hit(Track track, int channel, int pitch, double time) throws Exception {
        long tick = Math.round(time * 10_000);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, 96), tick));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), tick + 300));
    }
}
