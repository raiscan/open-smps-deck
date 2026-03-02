package com.opensmpsdeck.io;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectFileHierarchy {

    @TempDir File tempDir;

    @Test
    void hierarchicalSongRoundTrips() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        assertEquals(ArrangementMode.HIERARCHICAL, loaded.getArrangementMode());
        assertNotNull(loaded.getHierarchicalArrangement());

        var arr = loaded.getHierarchicalArrangement();
        assertEquals(2, arr.getPhraseLibrary().getAllPhrases().size());

        var phrase = arr.getPhraseLibrary().getPhrase(1);
        assertNotNull(phrase);
        assertEquals("Verse", phrase.getName());
        assertEquals(ChannelType.FM, phrase.getChannelType());
        assertEquals(4, phrase.getData().length);
    }

    @Test
    void chainEntriesRoundTrip() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        var chain = loaded.getHierarchicalArrangement().getChain(0);
        assertEquals(2, chain.getEntries().size());
        assertEquals(1, chain.getEntries().get(0).getPhraseId());
        assertEquals(5, chain.getEntries().get(1).getTransposeSemitones());
        assertEquals(2, chain.getEntries().get(1).getRepeatCount());
        assertEquals(0, chain.getLoopEntryIndex());
    }

    @Test
    void subPhraseRefsRoundTrip() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        var phrase = loaded.getHierarchicalArrangement().getPhraseLibrary().getPhrase(1);
        assertEquals(1, phrase.getSubPhraseRefs().size());
        assertEquals(2, phrase.getSubPhraseRefs().get(0).phraseId());
        assertEquals(3, phrase.getSubPhraseRefs().get(0).insertAtRow());
    }

    private Song createHierarchicalSong() {
        var song = new Song();
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        var arr = new HierarchicalArrangement();
        var p1 = arr.getPhraseLibrary().createPhrase("Verse", ChannelType.FM);
        p1.setData(new byte[]{(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x18});
        var p2 = arr.getPhraseLibrary().createPhrase("Bass", ChannelType.FM);
        p2.setData(new byte[]{(byte) 0x91, 0x18});

        p1.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p2.getId(), 3, 1));

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p1.getId()));
        var e2 = new ChainEntry(p1.getId());
        e2.setTransposeSemitones(5);
        e2.setRepeatCount(2);
        chain.getEntries().add(e2);
        chain.setLoopEntryIndex(0);

        song.setHierarchicalArrangement(arr);
        return song;
    }
}
