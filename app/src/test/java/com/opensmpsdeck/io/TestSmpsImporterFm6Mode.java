package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 7-FM-entry songs (S1/S2/S3K Special Stage and Chaos Emerald jingles) use a
 * sixth FM track in place of DAC: header entry 0 is a stub DAC and entry 6 is
 * FM6 on the hardware channel DAC would use. Import must map FM6 onto model
 * channel 5 with FM semantics, and the compiler must emit the 7-entry header
 * back.
 */
class TestSmpsImporterFm6Mode {

    @TempDir
    Path tempDir;

    /** Seven FM entries: stub DAC, five small FM tracks, FM6 with notes. */
    private byte[] buildSevenEntrySong() {
        int header = 6 + 7 * 4;
        int dacStub = header;          // F2
        int fmSmall = dacStub + 1;     // shared tiny track for FM1-5
        int fm6Track = fmSmall + 3;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0); // no voices
        out.write(7);               // SEVEN FM entries
        out.write(0);               // no PSG
        out.write(0x01);
        out.write(0x80);
        // entry 0: DAC stub
        out.write(dacStub & 0xFF); out.write((dacStub >> 8) & 0xFF);
        out.write(0); out.write(0);
        // entries 1-5: FM1-FM5 -> shared tiny track
        for (int i = 0; i < 5; i++) {
            out.write(fmSmall & 0xFF); out.write((fmSmall >> 8) & 0xFF);
            out.write(0); out.write(0);
        }
        // entry 6: FM6
        out.write(fm6Track & 0xFF); out.write((fm6Track >> 8) & 0xFF);
        out.write(0); out.write(0);

        out.write(0xF2);                       // DAC stub
        out.write(0xA1); out.write(0x08);      // FM small
        out.write(0xF2);
        out.write(0xC4); out.write(0x0C);      // FM6 notes
        out.write(0xC8); out.write(0x0C);
        out.write(0xF2);
        return out.toByteArray();
    }

    @Test
    void seventhEntryBecomesFm6OnChannelFive() throws Exception {
        File file = tempDir.resolve("test.sm2").toFile();
        Files.write(file.toPath(), buildSevenEntrySong());

        Song song = new SmpsImporter().importFile(file);
        assertTrue(song.isDacChannelFm6(), "7-entry header should set FM6 mode");

        var hier = song.getHierarchicalArrangement();
        var chain = hier.getChain(5);
        assertFalse(chain.getEntries().isEmpty(), "Channel 5 should hold the FM6 track");

        Phrase first = hier.getPhraseLibrary()
                .getPhrase(chain.getEntries().get(0).getPhraseId());
        assertEquals(ChannelType.FM, first.getChannelType(),
                "FM6 phrases are FM, not DAC");
        assertEquals((byte) 0xC4, first.getDataDirect()[0],
                "FM6 notes should land on channel 5, replacing the DAC stub");
    }

    @Test
    void compilerEmitsSevenEntryHeaderBack() throws Exception {
        File file = tempDir.resolve("test.sm2").toFile();
        Files.write(file.toPath(), buildSevenEntrySong());
        Song song = new SmpsImporter().importFile(file);

        byte[] compiled = new PatternCompiler().compile(song);
        assertEquals(7, compiled[2] & 0xFF, "Compiled header should have 7 FM entries");

        // Entry 0 must be a stub DAC (track is just STOP)
        int dacPtr = (compiled[6] & 0xFF) | ((compiled[7] & 0xFF) << 8);
        assertEquals(0xF2, compiled[dacPtr] & 0xFF, "Entry 0 should be a stub DAC track");

        // Entry 6 must contain the FM6 notes
        int fm6Ptr = (compiled[6 + 6 * 4] & 0xFF) | ((compiled[7 + 6 * 4] & 0xFF) << 8);
        boolean found = false;
        for (int i = fm6Ptr; i < compiled.length - 1; i++) {
            if ((compiled[i] & 0xFF) == 0xC4 && (compiled[i + 1] & 0xFF) == 0x0C) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Entry 6 should carry the FM6 notes");
    }

    @Test
    void sixEntrySongsStayInDacMode() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0);
        out.write(2); out.write(0);
        out.write(0x01); out.write(0x80);
        int dac = 6 + 8;
        out.write(dac & 0xFF); out.write(0); out.write(0); out.write(0);
        out.write((dac + 1) & 0xFF); out.write(0); out.write(0); out.write(0);
        out.write(0xF2);
        out.write(0xA1); out.write(0x08); out.write(0xF2);
        File file = tempDir.resolve("plain.sm2").toFile();
        Files.write(file.toPath(), out.toByteArray());

        Song song = new SmpsImporter().importFile(file);
        assertFalse(song.isDacChannelFm6(), "Normal songs keep channel 5 as DAC");
    }
}
