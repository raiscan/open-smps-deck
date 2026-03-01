package com.opensmps.deck;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.codec.PatternCompiler;
import com.opensmps.deck.io.WavExporter;
import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack integration tests that validate the complete pipeline:
 * create Song -> compile -> play -> export WAV -> verify.
 *
 * <p>Each test exercises FM, PSG, and DAC channels together, ensuring
 * that the model, compiler, playback engine, and WAV exporter work
 * end-to-end without requiring external resources.
 */
class TestFullStackRoundTrip {

    @TempDir
    File tempDir;

    /**
     * Create a Song with FM, PSG, and DAC channels in S2 mode,
     * compile it, load into PlaybackEngine, render a buffer,
     * and verify non-silent output.
     */
    @Test
    void testCreateCompilePlayS2() {
        Song song = createFullSong(SmpsMode.S2);

        // Compile
        PatternCompiler compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);
        assertNotNull(smps, "Compiled SMPS data should not be null");
        assertTrue(smps.length > 0, "Compiled SMPS data should not be empty");

        // Load into playback engine and render
        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);

        assertTrue(renderContainsAudio(engine),
                "S2 song with FM, PSG, and DAC channels should produce non-silent audio");
    }

    /**
     * Create the same song, export via WavExporter to a temp file, and
     * verify the WAV file has a correct RIFF/WAVE header, non-zero size,
     * and non-silent audio data.
     */
    @Test
    void testCreateCompileExportWav() throws IOException {
        Song song = createFullSong(SmpsMode.S2);
        File wavFile = new File(tempDir, "roundtrip.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(2);
        exporter.export(song, wavFile);

        assertTrue(wavFile.exists(), "WAV file should be created");
        assertTrue(wavFile.length() > 44, "WAV file should contain audio data beyond the 44-byte header");

        byte[] wavData = Files.readAllBytes(wavFile.toPath());

        // Verify RIFF header (first 4 bytes)
        String riffMarker = new String(wavData, 0, 4);
        assertEquals("RIFF", riffMarker, "First 4 bytes should be 'RIFF'");

        // Verify WAVE marker (bytes 8-11)
        String waveMarker = new String(wavData, 8, 4);
        assertEquals("WAVE", waveMarker, "Bytes 8-11 should be 'WAVE'");

        // Verify non-zero file size (data chunk must have content)
        assertTrue(wavData.length > 44, "WAV file should have non-zero audio data size");

        // Verify non-silent audio data after the 44-byte header
        boolean hasNonSilent = false;
        for (int i = 44; i < wavData.length; i++) {
            if (wavData[i] != 0) {
                hasNonSilent = true;
                break;
            }
        }
        assertTrue(hasNonSilent, "WAV audio data should contain non-silent samples");
    }

    /**
     * Create the same song in S1 mode and S2 mode, compile both,
     * render audio from both, and verify the rendered buffers differ,
     * proving that the SMPS mode actually affects output.
     */
    @Test
    void testS1ModeProducesDifferentOutput() {
        Song songS2 = createFullSong(SmpsMode.S2);
        Song songS1 = createFullSong(SmpsMode.S1);

        PlaybackEngine engineS2 = new PlaybackEngine();
        engineS2.loadSong(songS2);
        short[] bufferS2 = new short[8192];
        engineS2.renderBuffer(bufferS2);

        PlaybackEngine engineS1 = new PlaybackEngine();
        engineS1.loadSong(songS1);
        short[] bufferS1 = new short[8192];
        engineS1.renderBuffer(bufferS1);

        // Both should produce audio
        assertTrue(containsNonZero(bufferS2), "S2 mode should produce non-silent audio");
        assertTrue(containsNonZero(bufferS1), "S1 mode should produce non-silent audio");

        // The buffers should differ because the modes use different tempo modes
        // (S1=TIMEOUT, S2=OVERFLOW2) and note compensation (+1 shift in S1)
        boolean differ = false;
        for (int i = 0; i < bufferS2.length; i++) {
            if (bufferS2[i] != bufferS1[i]) {
                differ = true;
                break;
            }
        }
        assertTrue(differ, "S1 and S2 rendered audio should differ (different tempo mode and note compensation)");
    }

    // --- Helpers ---

    /**
     * Creates a Song with FM, PSG, and DAC channels populated.
     *
     * <p>FM1 (channel 0): sine-like voice, plays note C4 (0xA1) for 48 ticks.
     * PSG1 (channel 6): simple decay envelope, plays note C4 (0xA1) for 48 ticks.
     * DAC (channel 5): short sine wave sample, plays sample 0x81 for 48 ticks.
     * Order list points to the single pattern. Mode is configurable.
     */
    private Song createFullSong(SmpsMode mode) {
        Song song = new Song();
        song.setSmpsMode(mode);
        song.setTempo(0x80);
        song.setDividingTiming(1);

        // --- FM voice: simple sine-like (algo 0, op4 as sole carrier) ---
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00;   // algo 0, feedback 0
        voiceData[2] = 0x7F;   // Op1 TL = 127 (silent modulator)
        voiceData[7] = 0x7F;   // Op3 TL = 127 (silent modulator)
        voiceData[12] = 0x7F;  // Op2 TL = 127 (silent modulator)
        voiceData[16] = 0x01;  // Op4 DT_MUL: DT=0, MUL=1
        voiceData[17] = 0x00;  // Op4 TL = 0 (full volume carrier)
        voiceData[18] = 0x1F;  // Op4 RS_AR: RS=0, AR=31 (fastest attack)
        voiceData[19] = 0x00;  // Op4 AM_D1R: AM=0, D1R=0
        voiceData[20] = 0x00;  // Op4 D2R = 0
        voiceData[21] = 0x0F;  // Op4 D1L_RR: D1L=0, RR=15
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // --- PSG envelope: simple decay (volume 0 -> 3 -> 7 -> hold at 7) ---
        byte[] envelopeData = new byte[]{ 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, (byte) 0x80 };
        song.getPsgEnvelopes().add(new PsgEnvelope("Decay", envelopeData));

        // --- DAC sample: short sine wave as unsigned 8-bit PCM ---
        byte[] sineWave = new byte[256];
        for (int i = 0; i < sineWave.length; i++) {
            // Unsigned 8-bit: center at 0x80, amplitude 0x7F
            sineWave[i] = (byte) (0x80 + (int) (127.0 * Math.sin(2.0 * Math.PI * i / 32.0)));
        }
        song.getDacSamples().add(new DacSample("SineKick", sineWave, 0x0C));

        // --- Hierarchical arrangement: FM1 on channel 0, DAC on channel 5, PSG1 on channel 6 ---
        addPhrase(song, 0, new byte[]{
                (byte) 0xEF, 0x00,       // Set voice 0
                (byte) 0xA1, 0x30        // Note C4, duration 0x30
        });
        addPhrase(song, 5, new byte[]{
                (byte) 0x81, 0x30        // DAC sample 0, duration 0x30
        });
        addPhrase(song, 6, new byte[]{
                (byte) 0xF5, 0x00,       // Set PSG envelope 0
                (byte) 0xA1, 0x30        // Note C4, duration 0x30
        });
        setLoopOnActiveChains(song, 0);

        return song;
    }

    private static void addPhrase(Song song, int channel, byte[] data) {
        var arr = song.getHierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase(
                "Ch" + channel, ChannelType.fromChannelIndex(channel));
        phrase.setData(data);
        arr.getChain(channel).getEntries().add(new ChainEntry(phrase.getId()));
    }

    private static void setLoopOnActiveChains(Song song, int entryIndex) {
        var arr = song.getHierarchicalArrangement();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            if (!chain.getEntries().isEmpty() && entryIndex < chain.getEntries().size()) {
                chain.setLoopEntryIndex(entryIndex);
            }
        }
    }

    /**
     * Render multiple buffers from the engine and check for any non-zero samples.
     */
    private boolean renderContainsAudio(PlaybackEngine engine) {
        short[] buffer = new short[4096];
        for (int i = 0; i < 8; i++) {
            int samples = engine.renderBuffer(buffer);
            if (samples <= 0) continue;
            if (containsNonZero(buffer)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNonZero(short[] buffer) {
        for (short s : buffer) {
            if (s != 0) return true;
        }
        return false;
    }
}
