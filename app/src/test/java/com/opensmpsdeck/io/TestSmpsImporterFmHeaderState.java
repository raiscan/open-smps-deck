package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.Song;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SMPS headers carry per-channel FM key displacement and volume attenuation
 * (e.g. Sonic 2 songs attenuate FM channels by 0x0C). The hierarchical
 * arrangement must preserve them in the first phrase of each FM channel —
 * otherwise recompiled songs play FM channels ~9dB louder than the original
 * rip (user-visible as "the DAC/drums got quiet").
 */
class TestSmpsImporterFmHeaderState {

    @TempDir
    Path tempDir;

    /** One FM channel (after DAC) with header key 0xFC (-4) and volume 0x0C. */
    private byte[] buildSong() {
        int dacTrack = 6 + 2 * 4;
        int fm1Track = dacTrack + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0);   // no voice table
        out.write(2);                 // FM count (DAC + FM1)
        out.write(0);                 // PSG count
        out.write(0x01);              // timing
        out.write(0x80);              // tempo
        // DAC entry: ptr, key, vol
        out.write(dacTrack & 0xFF); out.write((dacTrack >> 8) & 0xFF);
        out.write(0); out.write(0);
        // FM1 entry: ptr, key=0xFC, vol=0x0C
        out.write(fm1Track & 0xFF); out.write((fm1Track >> 8) & 0xFF);
        out.write(0xFC); out.write(0x0C);

        out.write(0xF2);              // DAC: stop
        // FM1 track
        out.write(0xA1); out.write(0x0C);
        out.write(0xF2);
        return out.toByteArray();
    }

    @Test
    void fmHeaderKeyAndVolumeSurviveIntoTheArrangement() throws Exception {
        File file = tempDir.resolve("test.sm2").toFile();
        Files.write(file.toPath(), buildSong());

        Song song = new SmpsImporter().importFile(file);
        var hier = song.getHierarchicalArrangement();
        var chain = hier.getChain(0); // FM1
        assertFalse(chain.getEntries().isEmpty(), "FM1 chain should be populated");

        Phrase first = hier.getPhraseLibrary()
                .getPhrase(chain.getEntries().get(0).getPhraseId());
        byte[] data = first.getDataDirect();

        // The first phrase must start with the header state:
        // E9 <key> then E6 <vol> (matching prependFmHeaderState's pattern order)
        assertEquals((byte) SmpsCoordFlags.KEY_DISP, data[0],
                "First FM phrase should begin with KEY_DISP from the header");
        assertEquals((byte) 0xFC, data[1], "Header key offset value");
        assertEquals((byte) SmpsCoordFlags.VOLUME, data[2],
                "Header volume attenuation should follow");
        assertEquals((byte) 0x0C, data[3], "Header volume value");
        assertEquals((byte) 0xA1, data[4], "Original notes follow the prefix");
    }

    /** Same song but the FM1 track loops back to its own start (F6 jump). */
    private byte[] buildLoopingSong() {
        int dacTrack = 6 + 2 * 4;
        int fm1Track = dacTrack + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0);
        out.write(2);
        out.write(0);
        out.write(0x01);
        out.write(0x80);
        out.write(dacTrack & 0xFF); out.write((dacTrack >> 8) & 0xFF);
        out.write(0); out.write(0);
        out.write(fm1Track & 0xFF); out.write((fm1Track >> 8) & 0xFF);
        out.write(0xFC); out.write(0x0C);

        out.write(0xF2); // DAC: stop
        // FM1 track: note, then jump back to track start
        out.write(0xA1); out.write(0x0C);
        out.write(0xF6); out.write(fm1Track & 0xFF); out.write((fm1Track >> 8) & 0xFF);
        return out.toByteArray();
    }

    @Test
    void headerStateRunsOnceWhenTheChainLoopsToItsFirstEntry() throws Exception {
        File file = tempDir.resolve("looping.sm2").toFile();
        Files.write(file.toPath(), buildLoopingSong());

        Song song = new SmpsImporter().importFile(file);
        var hier = song.getHierarchicalArrangement();
        var chain = hier.getChain(0);

        // KEY_DISP and VOLUME are additive in the sequencer: if the loop
        // re-entered a phrase carrying the header prefix, the offsets would
        // accumulate every iteration. The prefix must live in a one-shot init
        // entry before the loop body.
        Phrase first = hier.getPhraseLibrary()
                .getPhrase(chain.getEntries().get(0).getPhraseId());
        byte[] initData = first.getDataDirect();
        assertEquals((byte) SmpsCoordFlags.KEY_DISP, initData[0],
                "Init entry should carry the header key displacement");
        assertEquals((byte) SmpsCoordFlags.VOLUME, initData[2],
                "Init entry should carry the header volume");

        assertTrue(chain.getEntries().size() >= 2, "Init entry + loop body");
        int loopIndex = chain.getLoopEntryIndex();
        assertTrue(loopIndex >= 1,
                "Loop must target the body, not the init entry (got " + loopIndex + ")");
        Phrase body = hier.getPhraseLibrary()
                .getPhrase(chain.getEntries().get(loopIndex).getPhraseId());
        for (byte b : body.getDataDirect()) {
            assertNotEquals((byte) SmpsCoordFlags.VOLUME, b,
                    "Loop body must not contain the additive volume prefix");
        }
    }

    @Test
    void zeroHeaderStateAddsNoPrefix() throws Exception {
        byte[] songData = buildSong();
        songData[12] = 0; // FM1 key
        songData[13] = 0; // FM1 vol
        File file = tempDir.resolve("plain.sm2").toFile();
        Files.write(file.toPath(), songData);

        Song song = new SmpsImporter().importFile(file);
        var hier = song.getHierarchicalArrangement();
        Phrase first = hier.getPhraseLibrary()
                .getPhrase(hier.getChain(0).getEntries().get(0).getPhraseId());
        assertEquals((byte) 0xA1, first.getDataDirect()[0],
                "No prefix when header key/vol are zero");
    }
}
