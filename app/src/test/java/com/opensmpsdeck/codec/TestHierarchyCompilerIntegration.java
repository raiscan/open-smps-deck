package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyCompilerIntegration {

    @Test
    void hierarchicalSongCompilesToValidSmps() {
        Song song = createHierarchicalTestSong();
        var compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Verify SMPS header
        assertNotNull(smps);
        assertTrue(smps.length > 6);
        assertEquals(2, smps[2]); // 2 FM channels (dummy DAC + FM1)
        assertEquals(0, smps[3]); // 0 PSG channels
    }

    @Test
    void hierarchicalSongPlaysBackViaSynth() {
        Song song = createHierarchicalTestSong();
        var compiler = new PatternCompiler();
        var result = compiler.compileDetailed(song);
        assertNotNull(result.getSmpsData());
        assertTrue(result.getSmpsData().length > 0);
    }

    private Song createHierarchicalTestSong() {
        var song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0x6E);

        // Add a simple FM voice
        var voice = new FmVoice("Sine", new byte[25]);
        voice.setAlgorithm(7);
        song.getVoiceBank().add(voice);

        // Build hierarchy
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Note", ChannelType.FM);
        // SET_VOICE 00, note C-5 (0xA1), duration 0x18
        phrase.setData(new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x18
        });

        arr.getChain(0).getEntries().add(new ChainEntry(phrase.getId()));
        song.setHierarchicalArrangement(arr);
        return song;
    }
}
