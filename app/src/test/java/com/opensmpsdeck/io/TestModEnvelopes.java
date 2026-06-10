package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modulation envelopes (Modulat.lst, same LST format as PSG.lst) drive S3K
 * vibrato via F1/F4 and the per-channel PSG header mod byte. They must be
 * imported, indexed 1-based at playback, applied from header state, and
 * persisted in projects.
 */
class TestModEnvelopes {

    @TempDir
    Path tempDir;

    /** Build an LST_ENV file with the given named envelopes. */
    private static byte[] lst(String[] names, byte[][] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("LST_ENV".getBytes(StandardCharsets.US_ASCII));
        out.write(names.length);
        for (int i = 0; i < names.length; i++) {
            byte[] n = names[i].getBytes(StandardCharsets.US_ASCII);
            out.write(n.length);
            out.writeBytes(n);
            out.write(data[i].length);
            out.writeBytes(data[i]);
        }
        return out.toByteArray();
    }

    /** Minimal S3K song: one PSG channel with header mod env 2. */
    private byte[] buildS3kSongWithHeaderMod() {
        int psgTrack = 6 + 6;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0); // no voices
        out.write(0);               // FM count
        out.write(1);               // PSG count
        out.write(0x01);
        out.write(0x80);
        // PSG entry: ptr, key, vol, MOD=2, ins
        out.write(psgTrack & 0xFF); out.write((psgTrack >> 8) & 0xFF);
        out.write(0); out.write(0);
        out.write(2);               // header modulation envelope id
        out.write(0);
        // PSG track
        out.write(0xB5); out.write(0x10);
        out.write(0xF2);
        return out.toByteArray();
    }

    @Test
    void importsModulatLstAndAppliesHeaderModEnv() throws Exception {
        Files.write(tempDir.resolve("Modulat.lst"), lst(
                new String[]{"Mod 1", "Mod 2"},
                new byte[][]{{0x01, (byte) 0x83}, {0x02, 0x03, (byte) 0x83}}));
        File song = tempDir.resolve("test.s3k").toFile();
        Files.write(song.toPath(), buildS3kSongWithHeaderMod());

        Song imported = new SmpsImporter().importFile(song);
        assertEquals(2, imported.getModEnvelopes().size(),
                "Modulat.lst envelopes should be imported");
        assertEquals("Mod 2", imported.getModEnvelopes().get(1).getName());

        // The PSG chain's first phrase must arm the header mod env via F4 (S3K)
        var chain = imported.getHierarchicalArrangement().getChain(6);
        assertFalse(chain.getEntries().isEmpty());
        Phrase first = imported.getHierarchicalArrangement().getPhraseLibrary()
                .getPhrase(chain.getEntries().get(0).getPhraseId());
        byte[] d = first.getDataDirect();
        assertEquals((byte) 0xF4, d[0], "Header mod env should become an F4 prefix");
        assertEquals(2, d[1], "F4 must carry the header envelope id");
    }

    @Test
    void headerModEnvIsNotEmittedForS2() throws Exception {
        // F4 means MODS_OFF in S2 — the prefix must only appear for S3K
        Files.write(tempDir.resolve("Modulat.lst"), lst(
                new String[]{"Mod 1"}, new byte[][]{{0x01, (byte) 0x83}}));
        byte[] data = buildS3kSongWithHeaderMod();
        File song = tempDir.resolve("test.sm2").toFile();
        Files.write(song.toPath(), data);

        Song imported = new SmpsImporter().importFile(song);
        var chain = imported.getHierarchicalArrangement().getChain(6);
        Phrase first = imported.getHierarchicalArrangement().getPhraseLibrary()
                .getPhrase(chain.getEntries().get(0).getPhraseId());
        assertNotEquals((byte) 0xF4, first.getDataDirect()[0],
                "S2 must not get an F4 prefix (F4 = MODS_OFF in S2)");
    }

    @Test
    void playbackIndexingIsOneBased() {
        SimpleSmpsData data = new SimpleSmpsData(new byte[]{0, 0, 0, 0, 1, 0});
        byte[][] envs = {{0x11}, {0x22}};
        data.setModEnvelopes(envs);
        assertNull(data.getModEnvelope(0), "Id 0 means no envelope");
        assertArrayEquals(new byte[]{0x11}, data.getModEnvelope(1));
        assertArrayEquals(new byte[]{0x22}, data.getModEnvelope(2));
        assertNull(data.getModEnvelope(3));
    }

    @Test
    void projectFileRoundTripsModEnvelopes() throws Exception {
        Song song = new Song();
        song.getModEnvelopes().add(new PsgEnvelope("Vibrato", new byte[]{1, 2, (byte) 0x83}));

        File file = tempDir.resolve("test.osmpsd").toFile();
        ProjectFile.save(song, file);
        Song loaded = ProjectFile.load(file);

        assertEquals(1, loaded.getModEnvelopes().size());
        assertEquals("Vibrato", loaded.getModEnvelopes().get(0).getName());
        assertArrayEquals(new byte[]{1, 2, (byte) 0x83},
                loaded.getModEnvelopes().get(0).getData());
    }
}
