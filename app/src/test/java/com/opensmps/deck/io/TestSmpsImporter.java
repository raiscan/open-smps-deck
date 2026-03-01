package com.opensmps.deck.io;

import com.opensmps.deck.codec.PatternCompiler;
import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestSmpsImporter {

    @TempDir
    File tempDir;

    @Test
    void testImportMinimalSong() {
        // Build a minimal SMPS binary:
        // Header: voice ptr at 0x12, 1 FM ch, 0 PSG, dividing=1, tempo=0x80
        // FM entry: track at 0x0A, key=0, vol=0
        // Track at 0x0A: EF 00 A1 30 F2
        // Voice at 0x12: 25 bytes
        byte[] smps = new byte[0x12 + 25];
        // Header
        smps[0] = 0x12; smps[1] = 0x00; // voice ptr
        smps[2] = 0x01; // 1 FM channel
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01; // dividing timing
        smps[5] = (byte) 0x80; // tempo
        // FM entry
        smps[6] = 0x0A; smps[7] = 0x00; // track ptr
        smps[8] = 0x00; // key offset
        smps[9] = 0x00; // vol offset
        // Track data at 0x0A
        smps[0x0A] = (byte) SmpsCoordFlags.SET_VOICE; // EF = set voice
        smps[0x0B] = 0x00;        // voice 0
        smps[0x0C] = (byte) 0xA1; // note C4
        smps[0x0D] = 0x30;        // duration
        smps[0x0E] = (byte) SmpsCoordFlags.STOP; // F2 = track end
        // Voice at 0x12 (leave as zeros = default voice)
        smps[0x12] = 0x32; // algo=2, fb=6

        Song song = new SmpsImporter().importData(smps, "test.bin");

        assertEquals("test", song.getName());
        assertEquals(1, song.getDividingTiming());
        assertEquals(0x80, song.getTempo());
        assertEquals(1, song.getVoiceBank().size());
        assertEquals(2, song.getVoiceBank().get(0).getAlgorithm());
        assertEquals(6, song.getVoiceBank().get(0).getFeedback());
        assertEquals(1, song.getPatterns().size());

        // Track data should be extracted
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        assertTrue(track.length > 0);
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, track[0]); // first byte is set voice (EF)
    }

    @Test
    void testImportFromFile() throws IOException {
        byte[] smps = buildMinimalSmps();
        File file = new File(tempDir, "song.bin");
        Files.write(file.toPath(), smps);

        Song song = new SmpsImporter().importFile(file);
        assertNotNull(song);
        assertEquals("song", song.getName()); // extension stripped
        assertTrue(song.getPatterns().size() > 0);
    }

    @Test
    void testImportPreservesHeader() {
        byte[] smps = buildMinimalSmps();
        smps[4] = 0x02; // dividing timing = 2
        smps[5] = (byte) 0xC0; // tempo = 0xC0

        Song song = new SmpsImporter().importData(smps, null);
        assertEquals(2, song.getDividingTiming());
        assertEquals(0xC0, song.getTempo());
    }

    @Test
    void testImportPreservesFmHeaderOffsetsAsLeadingFlags() {
        byte[] smps = new byte[0x10];
        smps[0] = 0x00; smps[1] = 0x00; // no voice table
        smps[2] = 0x01; // 1 FM channel
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0A; smps[7] = 0x00; // track ptr
        smps[8] = 0x02;                  // key offset
        smps[9] = (byte) 0xFF;           // volume offset -1
        smps[0x0A] = (byte) 0xA1;
        smps[0x0B] = 0x30;
        smps[0x0C] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "fm-offsets");
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertArrayEquals(new byte[]{
                (byte) SmpsCoordFlags.KEY_DISP, 0x02,
                (byte) SmpsCoordFlags.VOLUME, (byte) 0xFF,
                (byte) 0xA1, 0x30, (byte) SmpsCoordFlags.STOP
        }, track);
    }

    @Test
    void testImportPreservesPsgHeaderStateAsLeadingFlags() {
        byte[] smps = new byte[0x20];
        smps[0] = 0x00; smps[1] = 0x00; // no voice table
        smps[2] = 0x00; // 0 FM
        smps[3] = 0x01; // 1 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0C; smps[7] = 0x00;             // track ptr
        smps[8] = (byte) 0xFF;                      // key offset -1
        smps[9] = 0x03;                              // volume offset +3
        smps[10] = 0x05;                             // mod env (not preserved in model)
        smps[11] = 0x07;                             // PSG instrument
        smps[0x0C] = (byte) 0xA1;
        smps[0x0D] = 0x30;
        smps[0x0E] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "psg-state");
        byte[] track = song.getPatterns().get(0).getTrackData(6);
        assertArrayEquals(new byte[]{
                (byte) SmpsCoordFlags.KEY_DISP, (byte) 0xFF,
                (byte) SmpsCoordFlags.PSG_VOLUME, 0x03,
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x07,
                (byte) 0xA1, 0x30, (byte) SmpsCoordFlags.STOP
        }, track);
    }

    @Test
    void testImportSongWithPsgChannel() {
        // Build a song with 1 FM channel and 1 PSG channel
        Song original = new Song();
        original.setTempo(0x90);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        // Add a voice for the FM channel
        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x32; // algo=2, fb=6
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // FM channel 0: set voice, play a note, stop
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,               // note C4, duration 0x30
                (byte) SmpsCoordFlags.STOP
        });

        // PSG channel 0 (index 6): PSG instrument, note, stop
        pattern.setTrackData(6, new byte[]{
                (byte) SmpsCoordFlags.PSG_INSTRUMENT, 0x03,
                (byte) 0xB0, 0x18,               // note, duration
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile to SMPS binary
        byte[] smps = new PatternCompiler().compile(original);

        // Re-import
        Song imported = new SmpsImporter().importData(smps, "psg-test.bin");

        assertEquals(0x90, imported.getTempo());
        assertEquals(1, imported.getDividingTiming());
        assertTrue(imported.getPatterns().size() > 0);

        Pattern importedPattern = imported.getPatterns().get(0);

        // FM channel 0 should have track data
        byte[] fmTrack = importedPattern.getTrackData(0);
        assertNotNull(fmTrack);
        assertTrue(fmTrack.length > 0, "FM channel should have track data");

        // PSG channel 0 (index 6) should have track data
        byte[] psgTrack = importedPattern.getTrackData(6);
        assertNotNull(psgTrack);
        assertTrue(psgTrack.length > 0, "PSG channel should have track data");

        // Verify the PSG track contains the PSG_INSTRUMENT flag and the note
        boolean foundPsgInstrument = false;
        boolean foundNote = false;
        for (int i = 0; i < psgTrack.length; i++) {
            if ((psgTrack[i] & 0xFF) == SmpsCoordFlags.PSG_INSTRUMENT) {
                foundPsgInstrument = true;
            }
            if ((psgTrack[i] & 0xFF) == 0xB0) {
                foundNote = true;
            }
        }
        assertTrue(foundPsgInstrument, "PSG track should contain PSG_INSTRUMENT command");
        assertTrue(foundNote, "PSG track should contain the note byte 0xB0");
    }

    @Test
    void testImportSongWithMultipleFmChannels() {
        // Build a song with 3 FM channels, each with different notes
        Song original = new Song();
        original.setTempo(0x80);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x1A; // algo=2, fb=3
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // Channel 0: note 0xA1 (C4)
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 1: note 0xA5 (E4)
        pattern.setTrackData(1, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA5, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 2: note 0xA8 (G4)
        pattern.setTrackData(2, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA8, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile and reimport
        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "multi-fm.bin");

        Pattern importedPattern = imported.getPatterns().get(0);

        // All 3 channels should have track data
        byte[] track0 = importedPattern.getTrackData(0);
        byte[] track1 = importedPattern.getTrackData(1);
        byte[] track2 = importedPattern.getTrackData(2);

        assertNotNull(track0);
        assertNotNull(track1);
        assertNotNull(track2);
        assertTrue(track0.length > 0, "Channel 0 should have track data");
        assertTrue(track1.length > 0, "Channel 1 should have track data");
        assertTrue(track2.length > 0, "Channel 2 should have track data");

        // Each channel should have a distinct note byte
        assertTrue(containsByte(track0, (byte) 0xA1), "Channel 0 should contain note 0xA1");
        assertTrue(containsByte(track1, (byte) 0xA5), "Channel 1 should contain note 0xA5");
        assertTrue(containsByte(track2, (byte) 0xA8), "Channel 2 should contain note 0xA8");

        // Channels should not contain each other's notes
        assertFalse(containsByte(track0, (byte) 0xA5), "Channel 0 should not contain note 0xA5");
        assertFalse(containsByte(track1, (byte) 0xA8), "Channel 1 should not contain note 0xA8");
        assertFalse(containsByte(track2, (byte) 0xA1), "Channel 2 should not contain note 0xA1");
    }

    @Test
    void testImportRejectsTruncatedHeader() {
        // A 2-byte array is too short for the 6-byte SMPS header
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[2], "truncated");
        });

        // 5 bytes is still too short
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[5], "truncated");
        });

        // Empty array
        assertThrows(IllegalArgumentException.class, () -> {
            new SmpsImporter().importData(new byte[0], "empty");
        });
    }

    @Test
    void testImportHandlesEmptyTrackData() {
        // Build a song where one channel has only a rest (0x80) followed by stop
        Song original = new Song();
        original.setTempo(0x80);
        original.setDividingTiming(1);
        original.getPatterns().clear();
        original.getOrderList().clear();

        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        original.getVoiceBank().add(new FmVoice("Voice 0", voiceData));

        Pattern pattern = new Pattern(0, 64);

        // Channel 0: normal note
        pattern.setTrackData(0, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        });

        // Channel 1: only a rest (0x80 = rest note) + duration + stop
        pattern.setTrackData(1, new byte[]{
                (byte) SmpsCoordFlags.SET_VOICE, 0x00,
                (byte) 0x80, 0x30,               // rest, duration
                (byte) SmpsCoordFlags.STOP
        });

        original.getPatterns().add(pattern);
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        original.getOrderList().add(orderRow);

        // Compile and reimport
        byte[] smps = new PatternCompiler().compile(original);
        Song imported = new SmpsImporter().importData(smps, "rest-only.bin");

        Pattern importedPattern = imported.getPatterns().get(0);

        // Both channels should have track data present
        byte[] track0 = importedPattern.getTrackData(0);
        byte[] track1 = importedPattern.getTrackData(1);

        assertNotNull(track0);
        assertNotNull(track1);
        assertTrue(track0.length > 0, "Channel 0 should have track data");
        assertTrue(track1.length > 0, "Channel 1 (rest-only) should have track data");

        // The rest-only channel should contain the rest byte (0x80)
        assertTrue(containsByte(track1, (byte) 0x80), "Rest-only channel should contain rest byte 0x80");
    }

    private boolean containsByte(byte[] data, byte target) {
        for (byte b : data) {
            if (b == target) return true;
        }
        return false;
    }

    // --- SeqBase tests ---

    @Test
    void testSeqBaseAutoDetection() {
        // Simulate an SM2 file with Z80-absolute pointers.
        // Layout: header(6) + 1 FM entry(4) = 10 bytes of header.
        // SeqBase = 0x1380. Track data starts at file offset 10 (headerEnd).
        // So raw track pointer = 10 + 0x1380 = 0x138A.
        // Voice pointer = 0x1380 + 0x1A = 0x139A (voices after 0x1A bytes of track).
        int seqBase = 0x1380;
        int headerEnd = 10; // 6 + 1*4
        int trackFileOffset = headerEnd; // track data right after header
        int voiceFileOffset = trackFileOffset + 8; // voices 8 bytes into the track area

        byte[] smps = new byte[voiceFileOffset + FmVoice.VOICE_SIZE];
        // Header
        writeLE16(smps, 0, voiceFileOffset + seqBase); // voice ptr (Z80-absolute)
        smps[2] = 0x01; // 1 FM
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        // FM entry
        writeLE16(smps, 6, trackFileOffset + seqBase); // track ptr (Z80-absolute)
        smps[8] = 0x00; // key offset
        smps[9] = 0x00; // vol offset
        // Track data at file offset 10
        smps[trackFileOffset] = (byte) SmpsCoordFlags.SET_VOICE;
        smps[trackFileOffset + 1] = 0x00;
        smps[trackFileOffset + 2] = (byte) 0xA1;
        smps[trackFileOffset + 3] = 0x30;
        smps[trackFileOffset + 4] = (byte) SmpsCoordFlags.STOP;
        // Voice at voiceFileOffset
        smps[voiceFileOffset] = 0x32; // algo=2, fb=6

        Song song = new SmpsImporter().importData(smps, "z80-test");

        assertEquals(1, song.getVoiceBank().size());
        assertEquals(2, song.getVoiceBank().get(0).getAlgorithm());
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        assertTrue(containsByte(track, (byte) 0xA1), "Track should contain note 0xA1");
    }

    @Test
    void testFilenameEncodedOffset() {
        assertEquals(0x1380, SmpsImporter.parseFilenameOffset("song.1380.sm2"));
        assertEquals(0xABCD, SmpsImporter.parseFilenameOffset("My Song.ABCD.s3k"));
        assertEquals(-1, SmpsImporter.parseFilenameOffset("song.sm2"));
        assertEquals(-1, SmpsImporter.parseFilenameOffset("song.abc.sm2")); // 3 hex digits
        assertEquals(-1, SmpsImporter.parseFilenameOffset("song.12345.sm2")); // 5 hex digits
        assertEquals(-1, SmpsImporter.parseFilenameOffset("song.ZZZZ.sm2")); // not valid hex
    }

    @Test
    void testFilenameEncodedOffsetUsedByImportFile() throws IOException {
        // Build SMPS data with Z80-absolute pointers at base 0x1380
        int seqBase = 0x1380;
        int headerEnd = 10;
        int trackFileOffset = headerEnd;
        int voiceFileOffset = trackFileOffset + 5;

        byte[] smps = new byte[voiceFileOffset + FmVoice.VOICE_SIZE];
        writeLE16(smps, 0, voiceFileOffset + seqBase);
        smps[2] = 0x01;
        smps[3] = 0x00;
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        writeLE16(smps, 6, trackFileOffset + seqBase);
        smps[8] = 0x00;
        smps[9] = 0x00;
        smps[trackFileOffset] = (byte) 0xA1;
        smps[trackFileOffset + 1] = 0x30;
        smps[trackFileOffset + 2] = (byte) SmpsCoordFlags.STOP;
        smps[voiceFileOffset] = 0x32;

        File file = new File(tempDir, "Emerald Hill Zone.1380.sm2");
        Files.write(file.toPath(), smps);

        Song song = new SmpsImporter().importFile(file);
        assertEquals("Emerald Hill Zone", song.getName());
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(1, song.getVoiceBank().size());
    }

    // --- SmpsMode detection tests ---

    @Test
    void testSmsModeDetectedFromExtension() throws IOException {
        byte[] smps = buildMinimalSmps();

        File sm2 = new File(tempDir, "song.sm2");
        File s3k = new File(tempDir, "song.s3k");
        File smp = new File(tempDir, "song.smp");
        File bin = new File(tempDir, "song2.bin");
        Files.write(sm2.toPath(), smps);
        Files.write(s3k.toPath(), smps);
        Files.write(smp.toPath(), smps);
        Files.write(bin.toPath(), smps);

        assertEquals(SmpsMode.S2, new SmpsImporter().importFile(sm2).getSmpsMode());
        assertEquals(SmpsMode.S3K, new SmpsImporter().importFile(s3k).getSmpsMode());
        assertEquals(SmpsMode.S1, new SmpsImporter().importFile(smp).getSmpsMode());
        assertEquals(SmpsMode.S2, new SmpsImporter().importFile(bin).getSmpsMode());
    }

    // --- F6 Jump terminator stripping ---

    @Test
    void testImportStripsFJumpTerminator() {
        // Build SMPS with a track ending in F6 + 2-byte pointer instead of F2
        byte[] smps = new byte[0x12 + FmVoice.VOICE_SIZE];
        writeLE16(smps, 0, 0x12); // voice ptr
        smps[2] = 0x01; // 1 FM
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        writeLE16(smps, 6, 0x0A); // track ptr
        smps[8] = 0x00;
        smps[9] = 0x00;
        // Track at 0x0A: note, duration, F6 (jump), ptr_lo, ptr_hi
        smps[0x0A] = (byte) 0xA1; // note
        smps[0x0B] = 0x30;        // duration
        smps[0x0C] = (byte) SmpsCoordFlags.JUMP; // F6
        smps[0x0D] = 0x0A;        // jump target lo (stale)
        smps[0x0E] = 0x00;        // jump target hi (stale)
        // Voice at 0x12
        smps[0x12] = 0x32;

        Song song = new SmpsImporter().importData(smps, "jump-test");

        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        // Should end with F2 (STOP), not F6
        assertEquals((byte) SmpsCoordFlags.STOP, track[track.length - 1],
                "Track should end with F2 (STOP) after F6 stripping");
        // Should NOT contain F6
        assertFalse(containsByte(track, (byte) SmpsCoordFlags.JUMP),
                "Track should not contain F6 (JUMP) after stripping");
        // Should still contain the note
        assertTrue(containsByte(track, (byte) 0xA1));
    }

    @Test
    void testImportNormalizesInternalLoopPointerToTrackLocalOffset() {
        // Track starts at 0x0A and has an internal F7 loop pointer back to 0x0A.
        byte[] smps = new byte[0x20];
        writeLE16(smps, 0, 0x00); // no voice ptr
        smps[2] = 0x01; // 1 FM
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        writeLE16(smps, 6, 0x0A); // track ptr
        smps[8] = 0x00;
        smps[9] = 0x00;

        smps[0x0A] = (byte) SmpsCoordFlags.LOOP;
        smps[0x0B] = 0x00; // loop index
        smps[0x0C] = 0x02; // loop count
        smps[0x0D] = 0x0A; // pointer lo (absolute)
        smps[0x0E] = 0x00; // pointer hi (absolute)
        smps[0x0F] = (byte) 0xA1;
        smps[0x10] = 0x30;
        smps[0x11] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "loop-normalize");
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        assertTrue(track.length >= 5);
        assertEquals(SmpsCoordFlags.LOOP, track[0] & 0xFF);
        assertEquals(0x00, track[3] & 0xFF, "Pointer low byte should be track-local");
        assertEquals(0x00, track[4] & 0xFF, "Pointer high byte should be track-local");
    }

    @Test
    void testImportIncludesCalledSubroutinePastLinearTerminator() {
        // Track start 0x0A:
        //   F8 12 00  (CALL subroutine at 0x12)
        //   A1 10
        //   F2
        // Subroutine at 0x12:
        //   A4 08
        //   E3
        byte[] smps = new byte[0x20];
        writeLE16(smps, 0, 0x00); // no voice ptr
        smps[2] = 0x01; // 1 FM
        smps[3] = 0x00; // 0 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        writeLE16(smps, 6, 0x0A); // track ptr
        smps[8] = 0x00;
        smps[9] = 0x00;

        smps[0x0A] = (byte) SmpsCoordFlags.CALL;
        smps[0x0B] = 0x12; // abs pointer lo
        smps[0x0C] = 0x00; // abs pointer hi
        smps[0x0D] = (byte) 0xA1;
        smps[0x0E] = 0x10;
        smps[0x0F] = (byte) SmpsCoordFlags.STOP;
        smps[0x12] = (byte) 0xA4;
        smps[0x13] = 0x08;
        smps[0x14] = (byte) SmpsCoordFlags.RETURN;

        Song song = new SmpsImporter().importData(smps, "call-sub");
        byte[] track = song.getPatterns().get(0).getTrackData(0);
        assertNotNull(track);
        assertTrue(track.length >= 0x0B, "Imported track should include called subroutine bytes");
        assertEquals(SmpsCoordFlags.CALL, track[0] & 0xFF);
        assertEquals(0x08, track[1] & 0xFF, "CALL pointer should be normalized to local offset");
        assertEquals(0x00, track[2] & 0xFF);
        assertEquals((byte) 0xA4, track[0x08], "Subroutine note should exist in extracted data");
        assertEquals((byte) SmpsCoordFlags.RETURN, track[0x0A]);
    }

    // --- DPCM decompression ---

    @Test
    void testDpcmDecompression() {
        // Delta table: {0,1,2,4,8,16,32,64,-128,-1,-2,-4,-8,-16,-32,-64}
        // Input byte 0x12: high nibble=1 (delta=+1), low nibble=2 (delta=+2)
        // Accumulator starts at 0x80
        // Sample 0: 0x80 + 1 = 0x81
        // Sample 1: 0x81 + 2 = 0x83
        byte[] compressed = new byte[]{0x12};
        byte[] result = SmpsImporter.decompressDpcm(compressed);

        assertEquals(2, result.length);
        assertEquals((byte) 0x81, result[0]);
        assertEquals((byte) 0x83, result[1]);

        // Test wrapping: nibble 8 = delta -128
        // Input 0x80: high=8 (delta=-128), low=0 (delta=0)
        // Accumulator starts at 0x80
        // Sample 0: 0x80 + (-128) = 0x00 (wraps to 0)
        // Sample 1: 0x00 + 0 = 0x00
        compressed = new byte[]{(byte) 0x80};
        result = SmpsImporter.decompressDpcm(compressed);
        assertEquals((byte) 0x00, result[0]);
        assertEquals((byte) 0x00, result[1]);

        // Multi-byte test
        compressed = new byte[]{0x11, 0x11};
        result = SmpsImporter.decompressDpcm(compressed);
        assertEquals(4, result.length);
        // 0x80+1=0x81, 0x81+1=0x82, 0x82+1=0x83, 0x83+1=0x84
        assertEquals((byte) 0x81, result[0]);
        assertEquals((byte) 0x82, result[1]);
        assertEquals((byte) 0x83, result[2]);
        assertEquals((byte) 0x84, result[3]);
    }

    // --- PSG.lst parsing ---

    @Test
    void testPsgLstParsing() throws Exception {
        // Build a minimal PSG.lst binary:
        // "LST_ENV" + count(2) + envelope1 + envelope2
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("LST_ENV".getBytes(StandardCharsets.US_ASCII)); // header
        bos.write(2); // count = 2

        // Envelope 1: name="Env1", data={0x00, 0x01, 0x02, 0x80}
        byte[] name1 = "Env1".getBytes(StandardCharsets.US_ASCII);
        bos.write(name1.length);
        bos.write(name1);
        byte[] data1 = {0x00, 0x01, 0x02, (byte) 0x80};
        bos.write(data1.length);
        bos.write(data1);

        // Envelope 2: name="Env2", data={0x03, 0x04, 0x80}
        byte[] name2 = "Env2".getBytes(StandardCharsets.US_ASCII);
        bos.write(name2.length);
        bos.write(name2);
        byte[] data2 = {0x03, 0x04, (byte) 0x80};
        bos.write(data2.length);
        bos.write(data2);

        List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(bos.toByteArray());

        assertEquals(2, envelopes.size());
        assertEquals("Env1", envelopes.get(0).getName());
        assertEquals(3, envelopes.get(0).getStepCount()); // 3 steps before 0x80
        assertEquals("Env2", envelopes.get(1).getName());
        assertEquals(2, envelopes.get(1).getStepCount());
    }

    @Test
    void testPsgLstParsingRejectsInvalidHeader() {
        byte[] bad = "INVALID\u0000".getBytes(StandardCharsets.US_ASCII);
        List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(bad);
        assertTrue(envelopes.isEmpty());
    }

    // --- DAC.ini + DefDrum.txt parsing ---

    @Test
    void testDacIniParsing() throws IOException {
        // Create DAC.ini with INI sections (real SMPSPlay format)
        File dacIni = new File(tempDir, "DAC.ini");
        Files.writeString(dacIni.toPath(), """
                ; Comment
                [81]
                Compr = None
                File = kick.bin
                Rate = 0x0C
                """, StandardCharsets.UTF_8);

        // Create the raw DAC file
        byte[] kickData = {(byte) 0x80, (byte) 0x90, (byte) 0xA0};
        Files.write(new File(tempDir, "kick.bin").toPath(), kickData);

        // Create a minimal SMPS file to trigger importFile
        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "song.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(1, song.getDacSamples().size());
        assertEquals("kick", song.getDacSamples().get(0).getName());
        assertEquals(0x0C, song.getDacSamples().get(0).getRate());
        assertArrayEquals(kickData, song.getDacSamples().get(0).getData());
    }

    @Test
    void testDacIniWithDpcmCompression() throws IOException {
        File dacIni = new File(tempDir, "DAC.ini");
        Files.writeString(dacIni.toPath(), """
                [81]
                Compr = DPCM
                File = sample.bin
                Rate = 0x06
                """, StandardCharsets.UTF_8);

        // Create a DPCM-compressed sample (1 byte -> 2 samples)
        byte[] compressed = {0x12}; // delta +1, +2 -> samples 0x81, 0x83
        Files.write(new File(tempDir, "sample.bin").toPath(), compressed);

        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "dpcm-song.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(1, song.getDacSamples().size());
        byte[] dacData = song.getDacSamples().get(0).getData();
        assertEquals(2, dacData.length); // decompressed: 2 samples from 1 byte
        assertEquals((byte) 0x81, dacData[0]);
        assertEquals((byte) 0x83, dacData[1]);
    }

    @Test
    void testDefDrumTxtMapping() throws IOException {
        // DAC.ini with section for sample 81
        File dacIni = new File(tempDir, "DAC.ini");
        Files.writeString(dacIni.toPath(), """
                [81]
                Compr = None
                File = kick.bin
                Rate = 0x0C
                """, StandardCharsets.UTF_8);

        // DefDrum.txt maps two DAC notes to the same DAC sample at different rates
        File defDrum = new File(tempDir, "DefDrum.txt");
        Files.writeString(defDrum.toPath(), """
                [Drums]
                81\tDAC\t81\t0C
                82\tDAC\t81\t18
                """, StandardCharsets.UTF_8);

        byte[] kickData = {(byte) 0x80, (byte) 0x90};
        Files.write(new File(tempDir, "kick.bin").toPath(), kickData);

        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "drums.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(2, song.getDacSamples().size());
        // Both share the same sample data but different rates
        assertArrayEquals(kickData, song.getDacSamples().get(0).getData());
        assertArrayEquals(kickData, song.getDacSamples().get(1).getData());
        assertEquals(0x0C, song.getDacSamples().get(0).getRate());
        assertEquals(0x18, song.getDacSamples().get(1).getRate());
    }

    @Test
    void testDefDrumTxtSkipsNonDrumSections() throws IOException {
        // Mimic real Sonic 2 DefDrum.txt with [Main] section before [Drums]
        File dacIni = new File(tempDir, "DAC.ini");
        Files.writeString(dacIni.toPath(), """
                [81]
                Compr = None
                File = kick.bin
                Rate = 0x17
                [82]
                Compr = None
                File = snare.bin
                Rate = 0x01
                """, StandardCharsets.UTF_8);

        File defDrum = new File(tempDir, "DefDrum.txt");
        Files.writeString(defDrum.toPath(), """
                ; Sonic 2 drum definition
                [Main]
                DrumMode = Normal
                DrumIDBase = 81
                [Drums]
                81\tDAC\t81\t17
                82\tDAC\t82\t01
                """, StandardCharsets.UTF_8);

        Files.write(new File(tempDir, "kick.bin").toPath(), new byte[]{0x40, 0x50});
        Files.write(new File(tempDir, "snare.bin").toPath(), new byte[]{0x60, 0x70});

        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "real-format.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(2, song.getDacSamples().size());
        assertEquals("kick", song.getDacSamples().get(0).getName());
        assertEquals("snare", song.getDacSamples().get(1).getName());
        assertEquals(0x17, song.getDacSamples().get(0).getRate());
        assertEquals(0x01, song.getDacSamples().get(1).getRate());
    }

    @Test
    void testDacIniWithSubdirectory() throws IOException {
        // DAC.ini references files with DAC\ prefix (real SMPSPlay format)
        File dacIni = new File(tempDir, "DAC.ini");
        Files.writeString(dacIni.toPath(), """
                [81]
                Compr = DPCM
                File = DAC\\DAC_81.bin
                Rate = 0x17
                """, StandardCharsets.UTF_8);

        // Create DAC subdirectory with file
        File dacDir = new File(tempDir, "DAC");
        dacDir.mkdir();
        byte[] compressed = {0x12}; // DPCM: +1, +2
        Files.write(new File(dacDir, "DAC_81.bin").toPath(), compressed);

        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "subdir.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(1, song.getDacSamples().size());
        assertEquals("DAC_81", song.getDacSamples().get(0).getName());
        byte[] dacData = song.getDacSamples().get(0).getData();
        assertEquals(2, dacData.length);
        assertEquals((byte) 0x81, dacData[0]);
        assertEquals((byte) 0x83, dacData[1]);
    }

    @Test
    void testPsgLstCompanionLoading() throws IOException {
        // Build PSG.lst binary
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("LST_ENV".getBytes(StandardCharsets.US_ASCII));
        bos.write(1); // 1 envelope
        byte[] envName = "TestEnv".getBytes(StandardCharsets.US_ASCII);
        bos.write(envName.length);
        bos.write(envName);
        byte[] envData = {0x00, 0x01, (byte) 0x80};
        bos.write(envData.length);
        bos.write(envData);

        Files.write(new File(tempDir, "PSG.lst").toPath(), bos.toByteArray());

        byte[] smps = buildMinimalSmps();
        File smpsFile = new File(tempDir, "psg-song.sm2");
        Files.write(smpsFile.toPath(), smps);

        Song song = new SmpsImporter().importFile(smpsFile);

        assertEquals(1, song.getPsgEnvelopes().size());
        assertEquals("TestEnv", song.getPsgEnvelopes().get(0).getName());
        assertEquals(2, song.getPsgEnvelopes().get(0).getStepCount());
    }

    // --- PSG noise detection ---

    @Test
    void testPsgNoiseTrackMappedToNoiseChannel() {
        // Build SMPS with 0 FM, 3 PSG channels.
        // PSG channel 2 (third) contains F3 (PSG_NOISE) flag -> should map to channel 9.
        // Header: 6 bytes + 0 FM entries + 3 PSG entries (6 bytes each) = 24 bytes
        int headerEnd = 6 + 3 * 6; // = 24

        // Track offsets (right after header)
        int psg0Offset = headerEnd;       // PSG1: simple tone at channel 6
        int psg1Offset = psg0Offset + 5;  // PSG2: simple tone at channel 7
        int psg2Offset = psg1Offset + 5;  // PSG3: has F3 -> should go to channel 9

        int fileLen = psg2Offset + 7; // F3 XX note dur F2

        byte[] smps = new byte[fileLen];
        // Header
        writeLE16(smps, 0, 0x0000); // no voice table
        smps[2] = 0x00; // 0 FM
        smps[3] = 0x03; // 3 PSG
        smps[4] = 0x01; // dividing timing
        smps[5] = (byte) 0x80; // tempo

        // PSG entry 0
        writeLE16(smps, 6, psg0Offset);
        smps[8] = 0x00; // key
        smps[9] = 0x00; // vol
        smps[10] = 0x00; // mod
        smps[11] = 0x00; // instrument

        // PSG entry 1
        writeLE16(smps, 12, psg1Offset);
        smps[14] = 0x00;
        smps[15] = 0x00;
        smps[16] = 0x00;
        smps[17] = 0x00;

        // PSG entry 2
        writeLE16(smps, 18, psg2Offset);
        smps[20] = 0x00;
        smps[21] = 0x00;
        smps[22] = 0x00;
        smps[23] = 0x00;

        // PSG track 0: note + dur + STOP
        smps[psg0Offset] = (byte) 0xA1;
        smps[psg0Offset + 1] = 0x30;
        smps[psg0Offset + 2] = (byte) SmpsCoordFlags.STOP;
        // pad to 5 bytes
        smps[psg0Offset + 3] = 0x00;
        smps[psg0Offset + 4] = 0x00;

        // PSG track 1: note + dur + STOP
        smps[psg1Offset] = (byte) 0xA5;
        smps[psg1Offset + 1] = 0x30;
        smps[psg1Offset + 2] = (byte) SmpsCoordFlags.STOP;
        smps[psg1Offset + 3] = 0x00;
        smps[psg1Offset + 4] = 0x00;

        // PSG track 2: F3 (PSG_NOISE) + param + note + dur + STOP
        smps[psg2Offset] = (byte) SmpsCoordFlags.PSG_NOISE;
        smps[psg2Offset + 1] = (byte) 0xE7; // noise param
        smps[psg2Offset + 2] = (byte) 0xC6; // note
        smps[psg2Offset + 3] = 0x18;        // duration
        smps[psg2Offset + 4] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "noise-test");
        Pattern pattern = song.getPatterns().get(0);

        // PSG1 (channel 6) and PSG2 (channel 7) should have track data
        assertNotNull(pattern.getTrackData(6), "PSG1 should be on channel 6");
        assertNotNull(pattern.getTrackData(7), "PSG2 should be on channel 7");

        // PSG3 (channel 8) should be empty — the noise track went to channel 9
        assertEquals(0, pattern.getTrackData(8).length,
                "PSG3 (tone) should be empty when track has F3");

        // Channel 9 (Noise) should have the noise track data
        byte[] noiseTrack = pattern.getTrackData(9);
        assertNotNull(noiseTrack, "Noise channel (9) should have the F3 track data");
        assertTrue(containsByte(noiseTrack, (byte) SmpsCoordFlags.PSG_NOISE),
                "Noise track should contain the F3 flag");
        assertTrue(containsByte(noiseTrack, (byte) 0xC6),
                "Noise track should contain the note byte");
    }

    @Test
    void testContainsPsgNoiseFlag() {
        // Direct test of the scanner
        // Track with F3 at the start
        assertTrue(SmpsImporter.containsPsgNoiseFlag(new byte[]{
                (byte) SmpsCoordFlags.PSG_NOISE, (byte) 0xE7,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        }));

        // Track without F3
        assertFalse(SmpsImporter.containsPsgNoiseFlag(new byte[]{
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        }));

        // F3 appearing as a parameter to another flag should NOT trigger
        // E0 takes 1 param — if param happens to be 0xF3, it's not a flag
        assertFalse(SmpsImporter.containsPsgNoiseFlag(new byte[]{
                (byte) SmpsCoordFlags.PAN, (byte) 0xF3,
                (byte) 0xA1, 0x30,
                (byte) SmpsCoordFlags.STOP
        }));
    }

    @Test
    void testNoiseTrackHoistsF3BeforePrependedHeaderState() {
        // When a noise track has non-zero header vol/inst, prependPsgHeaderState
        // inserts EC (PSG_VOLUME) and F5 (PSG_INSTRUMENT) flags.  The F3 (PSG_NOISE)
        // flag MUST come first: setPsgVolume() calls refreshVolume() immediately,
        // and without noiseMode active the volume write hits hw ch 2 (tone 2)
        // instead of hw ch 3 (noise), causing an audible high-pitched tone.
        int headerEnd = 6 + 1 * 6; // 1 PSG channel
        int trackOffset = headerEnd;
        int fileLen = trackOffset + 5; // F3 param note dur STOP

        byte[] smps = new byte[fileLen];
        writeLE16(smps, 0, 0x0000); // no voice table
        smps[2] = 0x00; // 0 FM
        smps[3] = 0x01; // 1 PSG
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;

        // PSG entry with non-zero vol and instrument
        writeLE16(smps, 6, trackOffset);
        smps[8] = 0x00;         // key
        smps[9] = 0x02;         // vol (non-zero — triggers EC prepend)
        smps[10] = 0x00;        // mod
        smps[11] = 0x02;        // instrument (non-zero — triggers F5 prepend)

        // Track data: F3 E7 note dur STOP
        smps[trackOffset]     = (byte) SmpsCoordFlags.PSG_NOISE;
        smps[trackOffset + 1] = (byte) 0xE7;
        smps[trackOffset + 2] = (byte) 0xC6;
        smps[trackOffset + 3] = 0x18;
        smps[trackOffset + 4] = (byte) SmpsCoordFlags.STOP;

        Song song = new SmpsImporter().importData(smps, "noise-hoist-test");
        byte[] noiseTrack = song.getPatterns().get(0).getTrackData(9);
        assertNotNull(noiseTrack);

        // F3 must be the very first byte, before any EC or F5
        assertEquals(SmpsCoordFlags.PSG_NOISE, noiseTrack[0] & 0xFF,
                "F3 (PSG_NOISE) must be the first flag in noise track data");
        assertEquals(0xE7, noiseTrack[1] & 0xFF,
                "Noise parameter should immediately follow F3");

        // Verify EC and F5 appear after F3+param
        int ecPos = -1, f5Pos = -1;
        for (int i = 2; i < noiseTrack.length; i++) {
            if ((noiseTrack[i] & 0xFF) == SmpsCoordFlags.PSG_VOLUME && ecPos < 0) ecPos = i;
            if ((noiseTrack[i] & 0xFF) == SmpsCoordFlags.PSG_INSTRUMENT && f5Pos < 0) f5Pos = i;
        }
        assertTrue(ecPos > 1, "EC (PSG_VOLUME) should appear after F3+param");
        assertTrue(f5Pos > 1, "F5 (PSG_INSTRUMENT) should appear after F3+param");
    }

    // --- Helpers ---

    private void writeLE16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private byte[] buildMinimalSmps() {
        byte[] smps = new byte[0x12 + 25];
        smps[0] = 0x12; smps[1] = 0x00;
        smps[2] = 0x01;
        smps[3] = 0x00;
        smps[4] = 0x01;
        smps[5] = (byte) 0x80;
        smps[6] = 0x0A; smps[7] = 0x00;
        smps[8] = 0x00;
        smps[9] = 0x00;
        smps[0x0A] = (byte) 0xA1;
        smps[0x0B] = 0x30;
        smps[0x0C] = (byte) SmpsCoordFlags.STOP;
        return smps;
    }
}
