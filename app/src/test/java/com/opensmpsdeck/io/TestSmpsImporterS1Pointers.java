package com.opensmpsdeck.io;

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
 * Sonic 1 (.smp) rips use the SMPS 68k format: big-endian header pointers and
 * PC-relative in-stream jump pointers (dc.w loc-*-1). The importer must decode
 * these rather than assuming little-endian Z80 absolute pointers.
 */
class TestSmpsImporterS1Pointers {

    @TempDir
    Path tempDir;

    private static void writeBE16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Build a minimal S1-format binary:
     * header (BE pointers), DAC track, FM1 track with a PC-relative loop jump,
     * and a 2-voice table at the end.
     */
    private byte[] buildS1Binary() {
        // Layout:
        //  0x00: header (6 bytes) + 2 FM entries (4 bytes each) = 14 bytes
        //  0x0E: DAC track  (4 bytes: note, dur, F2, pad)
        //  0x12: FM1 track  (notes + F6 relative jump back to track start)
        //  voice table after tracks
        int dacTrack = 14;
        int fm1Track = 18;

        ByteArrayOutputStream fm1 = new ByteArrayOutputStream();
        fm1.write(0x85); // note
        fm1.write(0x08); // duration
        fm1.write(0x8C); // note
        fm1.write(0x08); // duration
        fm1.write(0xF6); // JUMP
        // PC-relative pointer: raw = target - ptrPos - 1 where ptrPos is the
        // file offset of the pointer word. Pointer word sits at fm1Track + 5.
        int ptrPos = fm1Track + 5;
        int raw = fm1Track - ptrPos - 1;
        ByteArrayOutputStream fm1WithPtr = new ByteArrayOutputStream();
        fm1WithPtr.writeBytes(fm1.toByteArray());
        writeBE16(fm1WithPtr, raw & 0xFFFF);

        int voicePtr = fm1Track + fm1WithPtr.size();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBE16(out, voicePtr); // voice table pointer (big-endian)
        out.write(2);             // FM track count (DAC + FM1)
        out.write(0);             // PSG track count
        out.write(0x01);          // dividing timing
        out.write(0x80);          // tempo
        // FM entry 0 (DAC)
        writeBE16(out, dacTrack);
        out.write(0);
        out.write(0);
        // FM entry 1 (FM1)
        writeBE16(out, fm1Track);
        out.write(0);
        out.write(0);

        // DAC track
        out.write(0x81); // sample note
        out.write(0x10); // duration
        out.write(0xF2); // STOP
        out.write(0x00); // pad

        out.writeBytes(fm1WithPtr.toByteArray());

        // Voice table: 2 voices with recognizable first bytes
        for (int v = 0; v < 2; v++) {
            for (int i = 0; i < 25; i++) {
                out.write(v == 0 ? 0x3C : 0x07);
            }
        }
        return out.toByteArray();
    }

    @Test
    void importsS1BigEndianHeaderAndRelativeJump() throws Exception {
        File file = tempDir.resolve("test song.smp").toFile();
        Files.write(file.toPath(), buildS1Binary());

        Song song = new SmpsImporter().importFile(file);

        // Voice table found via big-endian pointer
        assertEquals(2, song.getVoiceBank().size(),
                "S1 voice table should be located via big-endian pointer");
        assertEquals(0x3C, song.getVoiceBank().get(0).getData()[0] & 0xFF);

        // DAC channel (model ch 5) and FM1 (model ch 0) both populated
        var hier = song.getHierarchicalArrangement();
        assertFalse(hier.getChain(5).getEntries().isEmpty(), "DAC chain should be populated");
        assertFalse(hier.getChain(0).getEntries().isEmpty(), "FM1 chain should be populated");

        // The PC-relative F6 jump back to track start becomes a chain loop at entry 0
        assertEquals(0, hier.getChain(0).getLoopEntryIndex(),
                "Relative jump to track start should become loop at entry 0");

        // FM1 notes survive (with S1 -1 note compensation applied on import)
        Phrase fm1Phrase = hier.getPhraseLibrary()
                .getPhrase(hier.getChain(0).getEntries().get(0).getPhraseId());
        byte[] data = fm1Phrase.getDataDirect();
        assertEquals((byte) 0x84, data[0], "Note 0x85 should import as 0x84 (S1 compensation)");
        assertEquals((byte) 0x8B, data[2], "Note 0x8C should import as 0x8B (S1 compensation)");
    }
}
