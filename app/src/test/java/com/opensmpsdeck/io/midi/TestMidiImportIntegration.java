package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.Song;
import com.opensmpsdeck.model.SmpsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.*;
import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiImportIntegration {

    @TempDir
    File tempDir;

    /** Builds a tiny two-bar fixture: melody + bass + kick/hat drums at 120 BPM. */
    private File writeFixture() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track meta = seq.createTrack();
        meta.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte) 0xA1, 0x20}, 3), 0));

        Track melody = seq.createTrack();
        int[] pitches = {60, 64, 67, 72, 60, 64, 67, 72};
        for (int i = 0; i < pitches.length; i++) {
            melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitches[i], 100),
                    i * 480L));
            melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitches[i], 0),
                    i * 480L + 480));
        }
        Track drums = seq.createTrack();
        for (int beat = 0; beat < 8; beat++) {
            int pitch = beat % 2 == 0 ? 36 : 42; // kick / closed hat
            drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, pitch, 100),
                    beat * 480L));
            drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, pitch, 0),
                    beat * 480L + 120));
        }
        File f = new File(tempDir, "fixture.mid");
        MidiSystem.write(seq, 1, f);
        return f;
    }

    private Song importFixture() throws Exception {
        MidiStem stem = MidiReader.read(writeFixture());
        var suggestions = MappingSuggester.suggest(List.of(stem));
        var assignments = suggestions.stream()
                .map(s -> new MidiImportSpec.LineAssignment(s.stemName(), s.line(),
                        s.targetChannel(), 0, GmVoiceSuggestions.forProgram(s.gmProgram()), -1,
                        stem.ppq()))
                .toList();

        var fit = TempoFitter.fit(stem.tempoMap(), stem.totalTicks(), stem.ppq(), SmpsMode.S2);
        var drumNotes = stem.tracks().stream()
                .filter(MidiStem.MidiNoteTrack::drumTrack)
                .flatMap(t -> NoteQuantizer.quantize(t.notes(), stem.ppq()).stream())
                .toList();
        var split = GmDrumMapper.split(drumNotes, GmDrumMapper.defaultMapping());

        return MidiSongBuilder.build(new MidiImportSpec(
                "Fixture", SmpsMode.S2, fit.tempoByte(), fit.dividingTiming(),
                fit.unitsPerSixteenth(), 16, 4, true, stem.ppq(), assignments,
                split.dacHits(), split.noiseHits(), GmDrumMapper.defaultMapping(), Map.of()));
    }

    @Test
    void importedSongHasMelodyDrumsAndLoop() throws Exception {
        Song song = importFixture();
        var arr = song.getHierarchicalArrangement();
        assertFalse(arr.getChain(0).getEntries().isEmpty(), "melody on FM1");
        assertFalse(arr.getChain(5).getEntries().isEmpty(), "kicks on DAC");
        assertFalse(arr.getChain(9).getEntries().isEmpty(), "hats on noise");
        assertEquals(0, arr.getChain(0).getLoopEntryIndex());
    }

    @Test
    void importedSongCompilesAndRendersAudio() throws Exception {
        Song song = importFixture();
        assertTrue(new PatternCompiler().compile(song).length > 6);

        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);
        short[] buffer = new short[2048];
        long energy = 0;
        for (int i = 0; i < 100; i++) {           // ~2.3 s of audio
            engine.renderBuffer(buffer);
            for (short s : buffer) energy += Math.abs(s);
        }
        assertTrue(energy > 0, "rendered audio must not be silent");
    }
}
