package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1/S3K drivers (SMPSPlay InsMode=DEFAULT) store each 4-byte voice operator
 * parameter group as Op4,Op3,Op2,Op1; the chip layer and the FmVoice model
 * use the S2 order Op4,Op2,Op3,Op1. Import must unswap, compile must swap
 * back (so exports inject natively), and playback data must unswap again.
 */
class TestVoiceOperatorOrder {

    @TempDir
    Path tempDir;

    /** Native-order voice: byte 0 = alg/fb, groups of 4 with marker values. */
    private static byte[] nativeVoice() {
        byte[] v = new byte[FmVoice.VOICE_SIZE];
        v[0] = 0x3C;
        for (int g = 0; g < 6; g++) {
            for (int i = 0; i < 4; i++) {
                v[1 + g * 4 + i] = (byte) (0x10 * (g + 1) + i); // g?0, g?1, g?2, g?3
            }
        }
        return v;
    }

    private byte[] buildSongWithVoice(byte[] voice) {
        int header = 6 + 4; // 1 FM entry
        int track = header;
        int voicePtr = track + 5;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(voicePtr & 0xFF); out.write((voicePtr >> 8) & 0xFF);
        out.write(1); out.write(0);
        out.write(0x01); out.write(0x80);
        out.write(track & 0xFF); out.write((track >> 8) & 0xFF);
        out.write(0); out.write(0);
        out.write(0xEF); out.write(0x00);
        out.write(0xA5); out.write(0x10);
        out.write(0xF2);
        out.writeBytes(voice);
        return out.toByteArray();
    }

    @Test
    void s3kImportUnswapsMiddleOperatorBytes() throws Exception {
        File f = tempDir.resolve("v.s3k").toFile();
        Files.write(f.toPath(), buildSongWithVoice(nativeVoice()));
        Song song = new SmpsImporter().importFile(f);

        assertFalse(song.getVoiceBank().isEmpty());
        byte[] model = song.getVoiceBank().get(0).getData();
        // Native group g: [x0, x1, x2, x3] -> model: [x0, x2, x1, x3]
        assertEquals(0x10, model[1] & 0xFF);
        assertEquals(0x12, model[2] & 0xFF, "middle bytes swapped to chip order");
        assertEquals(0x11, model[3] & 0xFF);
        assertEquals(0x13, model[4] & 0xFF);
    }

    @Test
    void s2ImportKeepsBytesVerbatim() throws Exception {
        File f = tempDir.resolve("v.sm2").toFile();
        Files.write(f.toPath(), buildSongWithVoice(nativeVoice()));
        Song song = new SmpsImporter().importFile(f);
        assertArrayEquals(nativeVoice(), song.getVoiceBank().get(0).getData(),
                "S2 voices are already in chip order");
    }

    @Test
    void s3kCompileWritesNativeOrderAndPlaybackUnswaps() throws Exception {
        File f = tempDir.resolve("v.s3k").toFile();
        Files.write(f.toPath(), buildSongWithVoice(nativeVoice()));
        Song song = new SmpsImporter().importFile(f);
        byte[] modelOrder = song.getVoiceBank().get(0).getData();

        byte[] compiled = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(compiled, 0, 0, false);

        // Without the swap flag the binary holds native bytes (export shape)
        byte[] rawInFile = data.getVoice(0);
        assertArrayEquals(nativeVoice(), rawInFile,
                "Compiled S3K binary stores voices in native group order");

        // With the swap flag (playback path) the chip sees model order
        data.setVoiceOperatorSwap(true);
        assertArrayEquals(modelOrder, data.getVoice(0),
                "Playback hands the chip the unswapped voice");
    }

    @Test
    void s3kVoiceRoundTripsThroughReimport() throws Exception {
        File f = tempDir.resolve("v.s3k").toFile();
        Files.write(f.toPath(), buildSongWithVoice(nativeVoice()));
        Song song1 = new SmpsImporter().importFile(f);
        byte[] compiled = new PatternCompiler().compile(song1);

        Song song2 = new SmpsImporter().importData(compiled, "rt", 0, SmpsMode.S3K);
        assertArrayEquals(song1.getVoiceBank().get(0).getData(),
                song2.getVoiceBank().get(0).getData(),
                "Voice bytes must be stable across compile/re-import");
    }

    @Test
    void swapIsItsOwnInverse() {
        byte[] v = nativeVoice();
        assertArrayEquals(v, FmVoice.swapMiddleOperators(FmVoice.swapMiddleOperators(v)));
    }
}
