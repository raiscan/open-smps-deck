package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.*;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiReader {

    @TempDir
    File tempDir;

    private static MidiEvent noteOn(int ch, int pitch, int vel, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, vel), tick);
    }

    private static MidiEvent noteOff(int ch, int pitch, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick);
    }

    private File write(Sequence seq) throws Exception {
        File f = new File(tempDir, "t.mid");
        MidiSystem.write(seq, 1, f);
        return f;
    }

    @Test
    void pairsNoteOnAndOff() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));

        assertEquals(480, stem.ppq());
        assertEquals(1, stem.tracks().size());
        NoteEvent n = stem.tracks().get(0).notes().get(0);
        assertEquals(0, n.startTick());
        assertEquals(480, n.durationTicks());
        assertEquals(60, n.pitch());
        assertEquals(100, n.velocity());
    }

    @Test
    void velocityZeroNoteOnIsNoteOff() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOn(0, 60, 0, 240));   // running-status style note-off
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(240, stem.tracks().get(0).notes().get(0).durationTicks());
    }

    @Test
    void danglingNoteOnClosesAtTrackEnd() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 64, 90, 100));
        t.add(noteOn(0, 60, 90, 0));    // ensure track end > 100
        t.add(noteOff(0, 60, 960));
        MidiStem stem = MidiReader.read(write(seq));
        NoteEvent dangling = stem.tracks().get(0).notes().stream()
                .filter(n -> n.pitch() == 64).findFirst().orElseThrow();
        assertTrue(dangling.durationTicks() > 0);
    }

    @Test
    void samePitchOverlapMerges() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOn(0, 60, 100, 240)); // re-strike before off
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        var notes = stem.tracks().get(0).notes();
        assertEquals(2, notes.size());
        assertEquals(240, notes.get(0).durationTicks()); // first closed at re-strike
    }

    @Test
    void channelTenIsDrumTrack() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(9, 36, 100, 0));   // MIDI channel 10 = index 9
        t.add(noteOff(9, 36, 120));
        MidiStem stem = MidiReader.read(write(seq));
        assertTrue(stem.tracks().get(0).drumTrack());
    }

    @Test
    void readsTempoMapAndTimeSignature() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        // tempo 120 BPM = 500000 us/quarter
        t.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte) 0xA1, 0x20}, 3), 0));
        // time sig 3/4: num=3, denomPow=2, metronome=24, 32nds=8
        t.add(new MidiEvent(new MetaMessage(0x58, new byte[]{3, 2, 24, 8}, 4), 0));
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(500000, stem.tempoMap().get(0).microsecondsPerQuarter());
        assertEquals(3, stem.timeSignature().numerator());
        assertEquals(4, stem.timeSignature().denominator());
    }

    @Test
    void notelessTracksAreSkipped() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack(); // tempo-only track, no notes
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(1, stem.tracks().size());
    }
}
