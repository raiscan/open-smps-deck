package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.sound.midi.ShortMessage.NOTE_OFF;
import static javax.sound.midi.ShortMessage.NOTE_ON;
import static javax.sound.midi.ShortMessage.PROGRAM_CHANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidiScoreTest {

    @TempDir
    Path tempDir;

    @Test
    void readsConductorTempoAndSortsNotesAcrossTracks() throws Exception {
        var midi = new Sequence(Sequence.PPQ, 480);
        Track conductor = midi.createTrack();
        meta(conductor, 0, 0x51, new byte[]{0x09, 0x27, (byte) 0xc0}); // 600,000 µs per quarter
        meta(conductor, 480, 0x51, new byte[]{0x04, (byte) 0x93, (byte) 0xe0}); // 300,000 µs per quarter

        Track strings = midi.createTrack();
        meta(strings, 0, 0x03, "Countermelody".getBytes(UTF_8));
        message(strings, 0, PROGRAM_CHANGE, 0, 40, 0);
        message(strings, 240, NOTE_ON, 0, 65, 100);
        message(strings, 240, NOTE_ON, 0, 60, 90);
        message(strings, 960, NOTE_OFF, 0, 65, 0);
        message(strings, 960, NOTE_ON, 0, 60, 0);

        Track drums = midi.createTrack();
        message(drums, 0, NOTE_ON, 9, 36, 127);
        message(drums, 120, NOTE_OFF, 9, 36, 0);

        MidiScore score = read(midi, 1);
        assertEquals(List.of(new MidiScore.Part(0, "Countermelody", 40, false),
                new MidiScore.Part(1, "Drums", 0, true)), score.parts());
        assertEquals(List.of(new MidiScore.Note(1, 36, 127, 0, 150_000),
                new MidiScore.Note(0, 60, 90, 300_000, 900_000),
                new MidiScore.Note(0, 65, 100, 300_000, 900_000)), score.notes());
        assertTrue(score.warnings().isEmpty());
    }

    @Test
    void pairsOverlappingAttacksInOrderAcrossProgramChanges() throws Exception {
        var midi = new Sequence(Sequence.PPQ, 100);
        Track track = midi.createTrack();
        message(track, 0, NOTE_ON, 0, 60, 80);
        message(track, 10, PROGRAM_CHANGE, 0, 33, 0);
        message(track, 10, NOTE_ON, 0, 60, 90);
        message(track, 20, NOTE_OFF, 0, 60, 0);
        message(track, 30, NOTE_ON, 0, 60, 0);
        message(track, 40, PROGRAM_CHANGE, 0, 0, 0);
        message(track, 40, NOTE_ON, 0, 61, 70);
        message(track, 50, NOTE_OFF, 0, 61, 0);

        MidiScore score = read(midi, 0);
        assertEquals(List.of(new MidiScore.Part(0, "Acoustic Grand Piano", 0, false),
                new MidiScore.Part(1, "Electric Bass (finger)", 33, false)), score.parts());
        assertEquals(List.of(new MidiScore.Note(0, 60, 80, 0, 100_000),
                new MidiScore.Note(1, 60, 90, 50_000, 150_000),
                new MidiScore.Note(0, 61, 70, 200_000, 250_000)), score.notes());
    }

    @Test
    void reportsDanglingAttacksWithoutPairingAcrossTracksOrChannels() throws Exception {
        var midi = new Sequence(Sequence.PPQ, 480);
        Track first = midi.createTrack();
        Track second = midi.createTrack();
        message(first, 0, NOTE_ON, 0, 60, 80);
        message(first, 10, NOTE_OFF, 1, 60, 0);
        message(second, 12, NOTE_OFF, 0, 60, 0);
        message(second, 120, NOTE_ON, 0, 60, 90);
        message(second, 240, NOTE_OFF, 0, 60, 0);

        MidiScore score = read(midi, 1);
        assertEquals(2, score.parts().size());
        assertEquals(List.of(new MidiScore.Note(1, 60, 90, 125_000, 250_000)), score.notes());
        assertEquals(List.of("Unclosed MIDI note in track 1, channel 1, pitch 60 at tick 0"), score.warnings());
    }

    @Test
    void carriesFractionalMicrosecondsAcrossTempoChanges() throws Exception {
        var midi = new Sequence(Sequence.PPQ, 3);
        Track track = midi.createTrack();
        meta(track, 1, 0x51, new byte[]{0x07, (byte) 0xa1, 0x20}); // 500,000 µs per quarter
        meta(track, 2, 0x51, new byte[]{0x07, (byte) 0xa1, 0x21}); // 500,001 µs per quarter
        message(track, 1, NOTE_ON, 0, 60, 80);
        message(track, 3, NOTE_OFF, 0, 60, 0);

        assertEquals(List.of(new MidiScore.Note(0, 60, 80, 166_666, 500_000)), read(midi, 0).notes());
    }

    @Test
    void rejectsSmpteTiming() throws Exception {
        var midi = new Sequence(Sequence.SMPTE_25, 40);
        midi.createTrack();

        var error = assertThrows(IOException.class, () -> read(midi, 0));
        assertTrue(error.getMessage().contains("SMPTE"));
    }

    private MidiScore read(Sequence midi, int fileType) throws IOException {
        Path file = tempDir.resolve("score.mid");
        assertTrue(MidiSystem.write(midi, fileType, file.toFile()) > 0);
        return MidiScore.read(file);
    }

    private static void meta(Track track, long tick, int type, byte[] data) throws InvalidMidiDataException {
        track.add(new MidiEvent(new MetaMessage(type, data, data.length), tick));
    }

    private static void message(Track track, long tick, int command, int channel, int first, int second)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(new ShortMessage(command, channel, first, second), tick));
    }
}
