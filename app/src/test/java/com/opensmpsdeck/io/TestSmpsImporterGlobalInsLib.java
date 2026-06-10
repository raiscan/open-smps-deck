package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3K songs may point their voice table at a shared instrument library
 * (SMPSPlay's GlobalInsLib, e.g. InsSet.17D8.bin) instead of embedding voices.
 * When the header voice pointer resolves outside the song file, the importer
 * must load voices from the library: from the pointer's offset when it lands
 * mid-library, otherwise from the library start (SMPSPlay loader_smps.c).
 */
class TestSmpsImporterGlobalInsLib {

    private static final int LIB_BASE = 0x17D8;

    @TempDir
    Path tempDir;

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** Minimal S3K song based at 0x8000 whose voice pointer is the given raw Z80 address. */
    private byte[] buildS3kSong(int rawVoicePtr) {
        int base = 0x8000;
        int fm1Track = 6 + 4; // header + one FM entry
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLE16(out, rawVoicePtr);
        out.write(1);    // FM count
        out.write(0);    // PSG count
        out.write(0x01); // timing
        out.write(0x80); // tempo
        writeLE16(out, base + fm1Track);
        out.write(0);
        out.write(0);
        // FM1 track
        out.write(0x85);
        out.write(0x08);
        out.write(0xF2);
        return out.toByteArray();
    }

    /** Library with 3 voices whose first bytes are 0x10, 0x11, 0x12. */
    private void writeLibrary(Path dir) throws Exception {
        ByteArrayOutputStream lib = new ByteArrayOutputStream();
        for (int v = 0; v < 3; v++) {
            for (int i = 0; i < 25; i++) {
                lib.write(i == 0 ? 0x10 + v : 0x00);
            }
        }
        Files.write(dir.resolve(String.format("InsSet.%04X.bin", LIB_BASE)), lib.toByteArray());
    }

    private Song importSong(Path dir, int rawVoicePtr) throws Exception {
        File song = dir.resolve("test.8000.s3k").toFile();
        Files.write(song.toPath(), buildS3kSong(rawVoicePtr));
        return new SmpsImporter().importFile(song);
    }

    @Test
    void pointerAtLibraryBaseLoadsFullLibrary() throws Exception {
        writeLibrary(tempDir);
        Song song = importSong(tempDir, LIB_BASE);
        assertEquals(3, song.getVoiceBank().size(),
                "Pointer at library base should load the full library");
        assertEquals(0x10, song.getVoiceBank().get(0).getData()[0] & 0xFF);
    }

    @Test
    void pointerMidLibraryLoadsFromOffset() throws Exception {
        writeLibrary(tempDir);
        Song song = importSong(tempDir, LIB_BASE + 25);
        assertEquals(2, song.getVoiceBank().size(),
                "Pointer mid-library should load voices from that offset");
        assertEquals(0x11, song.getVoiceBank().get(0).getData()[0] & 0xFF);
    }

    @Test
    void pointerOutsideLibraryRangeLoadsFullLibrary() throws Exception {
        writeLibrary(tempDir);
        // Out-of-file AND out-of-library voice pointer (e.g. Launch Base's 0x92C8
        // with the original 0x9345 base) falls back to the full library
        Song song = importSong(tempDir, 0x0500);
        assertEquals(3, song.getVoiceBank().size(),
                "Out-of-range pointer should fall back to the full library");
    }

    @Test
    void libraryInParentDirectoryIsFound() throws Exception {
        writeLibrary(tempDir);
        Path subDir = Files.createDirectory(tempDir.resolve("Proto_1994-05-17"));
        Song song = importSong(subDir, LIB_BASE);
        assertEquals(3, song.getVoiceBank().size(),
                "Library in the parent directory should be used");
    }

    @Test
    void noLibraryMeansNoVoices() throws Exception {
        Song song = importSong(tempDir, LIB_BASE);
        assertTrue(song.getVoiceBank().isEmpty(),
                "Without a library the voice bank stays empty");
    }
}
