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
                GmVoiceSuggestions.squareLead(), -1 /* no PSG env */, 480 /* ppq */);
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
    void dataEqualVoicesShareOneBankSlot() {
        // two channels, each carrying a FRESH (distinct instance, data-equal) voice
        var lineA = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(0, 480, 60, 100)));
        var lineB = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(0, 480, 67, 100)));
        var spec = new MidiImportSpec(
                "Dedup", SmpsMode.S2, 0x80, 1, 4, 16, 4, false, 480,
                List.of(new MidiImportSpec.LineAssignment(
                                "A", lineA, 0, 0, GmVoiceSuggestions.squareLead(), -1, 480),
                        new MidiImportSpec.LineAssignment(
                                "B", lineB, 1, 0, GmVoiceSuggestions.squareLead(), -1, 480)),
                List.of(), List.of(), GmDrumMapper.defaultMapping(), Map.of());
        Song song = MidiSongBuilder.build(spec);

        assertEquals(1, song.getVoiceBank().size());
        for (int ch = 0; ch <= 1; ch++) {
            int firstId = song.getHierarchicalArrangement().getChain(ch)
                    .getEntries().get(0).getPhraseId();
            byte[] data = song.getHierarchicalArrangement().getPhraseLibrary()
                    .getPhrase(firstId).getData();
            assertEquals((byte) SmpsCoordFlags.SET_VOICE, data[0], "channel " + ch);
            assertEquals(0, data[1], "channel " + ch + " voice index");
        }
    }

    @Test
    void perStemPpqQuantizesEachLineCorrectly() {
        // identical musical content at different resolutions: a quarter note on
        // beat 2 — tick 480 len 480 at ppq 480, tick 960 len 960 at ppq 960
        var line480 = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(480, 480, 60, 100)));
        var line960 = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(960, 960, 60, 100)));
        var spec = new MidiImportSpec(
                "Ppq", SmpsMode.S2, 0x80, 1, 4, 16, 4, false, 480,
                List.of(new MidiImportSpec.LineAssignment(
                                "A", line480, 0, 0, GmVoiceSuggestions.squareLead(), -1, 480),
                        new MidiImportSpec.LineAssignment(
                                "B", line960, 1, 0, GmVoiceSuggestions.squareLead(), -1, 960)),
                List.of(), List.of(), GmDrumMapper.defaultMapping(), Map.of());
        Song song = MidiSongBuilder.build(spec);

        List<String> decoded0 = decodeFirstPhrase(song, 0);
        List<String> decoded1 = decodeFirstPhrase(song, 1);
        assertEquals(decoded0, decoded1,
                "same musical content at different ppq must produce identical rows");
    }

    private static List<String> decodeFirstPhrase(Song song, int channel) {
        int firstId = song.getHierarchicalArrangement().getChain(channel)
                .getEntries().get(0).getPhraseId();
        byte[] data = song.getHierarchicalArrangement().getPhraseLibrary()
                .getPhrase(firstId).getData();
        return SmpsDecoder.decode(data).stream()
                .map(r -> r.note() + ":" + r.duration())
                .toList();
    }

    @Test
    void builtSongCompiles() {
        Song song = MidiSongBuilder.build(basicSpec());
        byte[] smps = new PatternCompiler().compile(song);
        assertTrue(smps.length > 6);
    }
}
