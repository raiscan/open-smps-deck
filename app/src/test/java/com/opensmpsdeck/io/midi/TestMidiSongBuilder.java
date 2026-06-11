package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiSongBuilder {

    private MidiImportSpec basicSpec() {
        var line = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(0, 480, 60, 100),
                new NoteEvent(480, 480, 64, 100)));
        var assignment = new MidiImportSpec.LineAssignment(
                "Synth", line, 0 /* FM1 */, 0 /* octave shift */,
                GmVoiceSuggestions.squareLead(), -1 /* no PSG env */);
        return new MidiImportSpec(
                "Like We", SmpsMode.S2, 0xCC, 2, 4 /* unitsPerSixteenth */,
                16 /* stepsPerBar */, 4 /* barsPerPhrase */, true /* loop */,
                480 /* ppq */, List.of(assignment),
                List.of() /* drum hits */, List.of(),
                GmDrumMapper.defaultMapping(), Map.of());
    }

    @Test
    void buildsSongWithAssignedChannel() {
        Song song = MidiSongBuilder.build(basicSpec());
        assertEquals("Like We", song.getName());
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(0xCC, song.getTempo());
        assertEquals(2, song.getDividingTiming());
        assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode());

        Chain fm1 = song.getHierarchicalArrangement().getChain(0);
        assertFalse(fm1.getEntries().isEmpty());
        assertTrue(song.getHierarchicalArrangement().getChain(1).getEntries().isEmpty());
    }

    @Test
    void firstPhraseStartsWithVoiceCommand() {
        Song song = MidiSongBuilder.build(basicSpec());
        Chain fm1 = song.getHierarchicalArrangement().getChain(0);
        int firstId = fm1.getEntries().get(0).getPhraseId();
        byte[] data = song.getHierarchicalArrangement().getPhraseLibrary()
                .getPhrase(firstId).getData();
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, data[0]);
        assertEquals(0, data[1]); // voice index 0 in the bank
        assertEquals(1, song.getVoiceBank().size());
    }

    @Test
    void loopWholeSongSetsLoopEntry() {
        Song song = MidiSongBuilder.build(basicSpec());
        assertEquals(0, song.getHierarchicalArrangement().getChain(0).getLoopEntryIndex());
    }

    @Test
    void drumHitsCreateDacChainAndPlaceholderSamples() {
        var spec = basicSpec();
        var withDrums = new MidiImportSpec(
                spec.songName(), spec.mode(), spec.tempoByte(), spec.dividingTiming(),
                spec.unitsPerSixteenth(), spec.stepsPerBar(), spec.barsPerPhrase(),
                spec.loopWholeSong(), spec.ppq(), spec.lines(),
                List.of(new GmDrumMapper.DrumHit(0, 1, GmDrumMapper.DrumTarget.DAC_KICK),
                        new GmDrumMapper.DrumHit(2, 1, GmDrumMapper.DrumTarget.DAC_SNARE)),
                List.of(new GmDrumMapper.DrumHit(1, 1, GmDrumMapper.DrumTarget.NOISE_SHORT)),
                spec.drumMapping(), spec.dacSampleOverrides());
        Song song = MidiSongBuilder.build(withDrums);

        assertFalse(song.getHierarchicalArrangement().getChain(5).getEntries().isEmpty());
        assertFalse(song.getHierarchicalArrangement().getChain(9).getEntries().isEmpty());
        // placeholder samples for the two used DAC slots (kick=0, snare=1)
        assertEquals(2, song.getDacSamples().size());
        assertEquals("Kick", song.getDacSamples().get(0).getName());
        assertEquals("Snare", song.getDacSamples().get(1).getName());
    }

    @Test
    void builtSongCompiles() {
        Song song = MidiSongBuilder.build(basicSpec());
        byte[] smps = new PatternCompiler().compile(song);
        assertTrue(smps.length > 6);
    }
}
