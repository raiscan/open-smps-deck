package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.PatternCompiler;
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
 * Real rips (e.g. Sonic 2's Super Sonic theme) place shared subroutines BEFORE
 * the track start pointers; tracks reach them via F8 CALL. The importer must
 * keep those subroutine bytes and remap the CALL pointers, otherwise the
 * channel decompiles to garbage and playback desynchronises.
 */
class TestSmpsImporterPreStartSubroutine {

    @TempDir
    Path tempDir;

    private static final int[] SUB_NOTES = {0xD4, 0x0F, 0xD0, 0x06};

    /**
     * Layout: header, DAC stop track, subroutine (before FM1's start), FM1 track
     * that CALLs back to the subroutine.
     */
    private byte[] buildBinary() {
        int headerSize = 6 + 2 * 4; // 2 FM entries (DAC + FM1)
        int dacTrack = headerSize;          // 1 byte: F2
        int sub = dacTrack + 1;             // D4 0F D0 06 E3
        int fm1Track = sub + 5;             // starts AFTER the subroutine

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0);   // no voice table
        out.write(2);                 // FM count
        out.write(0);                 // PSG count
        out.write(0x01);              // timing
        out.write(0x80);              // tempo
        // DAC entry
        out.write(dacTrack & 0xFF); out.write((dacTrack >> 8) & 0xFF);
        out.write(0); out.write(0);
        // FM1 entry
        out.write(fm1Track & 0xFF); out.write((fm1Track >> 8) & 0xFF);
        out.write(0); out.write(0);

        out.write(0xF2); // DAC: stop

        // Subroutine (before FM1's start pointer)
        for (int b : SUB_NOTES) out.write(b);
        out.write(0xE3); // RETURN

        // FM1 track: note, CALL back to subroutine, note, stop
        out.write(0xC8); out.write(0x0C);
        out.write(0xF8); out.write(sub & 0xFF); out.write((sub >> 8) & 0xFF);
        out.write(0xC9); out.write(0x0C);
        out.write(0xF2);
        return out.toByteArray();
    }

    @Test
    void subroutineBeforeTrackStartSurvivesImportAndRecompile() throws Exception {
        File file = tempDir.resolve("test.sm2").toFile();
        Files.write(file.toPath(), buildBinary());

        Song song = new SmpsImporter().importFile(file);
        var hier = song.getHierarchicalArrangement();

        // The subroutine's notes must exist somewhere in the imported phrases
        boolean subFound = false;
        for (Phrase p : hier.getPhraseLibrary().getAllPhrases()) {
            if (containsSequence(p.getDataDirect(), SUB_NOTES)) {
                subFound = true;
                break;
            }
        }
        assertTrue(subFound,
                "Subroutine notes (called from before track start) should survive import");

        // And the recompiled binary must contain them too
        byte[] compiled = new PatternCompiler().compile(song, song.getSmpsMode());
        assertTrue(containsSequence(compiled, SUB_NOTES),
                "Recompiled binary should contain the subroutine notes");

        // Main-stream notes also survive, in order
        assertTrue(containsSequence(compiled, new int[]{0xC8, 0x0C}),
                "First main note should survive");
        assertTrue(containsSequence(compiled, new int[]{0xC9, 0x0C}),
                "Post-call note should survive");
    }

    private static boolean containsSequence(byte[] data, int[] seq) {
        outer:
        for (int i = 0; i + seq.length <= data.length; i++) {
            for (int j = 0; j < seq.length; j++) {
                if ((data[i + j] & 0xFF) != seq[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
