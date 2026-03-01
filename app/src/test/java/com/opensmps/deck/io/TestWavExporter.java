package com.opensmps.deck.io;

import com.opensmps.deck.model.ChainEntry;
import com.opensmps.deck.model.ChannelType;
import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Pattern;
import com.opensmps.deck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TestWavExporter {

    @TempDir
    File tempDir;

    private static void addPhrase(Song song, int channel, byte[] data) {
        var arr = song.getHierarchicalArrangement();
        int end = data.length;
        while (end > 0 && (data[end - 1] & 0xFF) == 0xF2) end--;
        byte[] phraseData = end < data.length ? Arrays.copyOf(data, end) : data;
        var phrase = arr.getPhraseLibrary().createPhrase(
            "Ch" + channel, ChannelType.fromChannelIndex(channel));
        phrase.setData(phraseData);
        arr.getChain(channel).getEntries().add(new ChainEntry(phrase.getId()));
    }

    private static void addPhraseRaw(Song song, int channel, byte[] data) {
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

    @Test
    void testExportProducesValidWavFile() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1); // 1 second max to keep test fast
        exporter.export(song, wavFile);

        assertTrue(wavFile.exists(), "WAV file should be created");
        assertTrue(wavFile.length() > 44, "WAV file should have audio data beyond the 44-byte header");

        // Read and verify WAV header markers
        byte[] data = Files.readAllBytes(wavFile.toPath());
        String riff = new String(data, 0, 4);
        assertEquals("RIFF", riff, "File should start with RIFF marker");

        String wave = new String(data, 8, 4);
        assertEquals("WAVE", wave, "File should contain WAVE marker");

        String fmt = new String(data, 12, 4);
        assertEquals("fmt ", fmt, "File should contain fmt  marker");
    }

    @Test
    void testExportSampleRateIs44100() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test-rate.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1);
        exporter.export(song, wavFile);

        // Sample rate is at byte offset 24 in the WAV header (little-endian 32-bit)
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            raf.seek(24);
            int b0 = raf.read();
            int b1 = raf.read();
            int b2 = raf.read();
            int b3 = raf.read();
            int sampleRate = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            assertEquals(44100, sampleRate, "Sample rate should be 44100 Hz");
        }
    }

    @Test
    void testLoopCountGetterSetter() {
        WavExporter exporter = new WavExporter();
        assertEquals(2, exporter.getLoopCount(), "Default loop count should be 2");

        exporter.setLoopCount(5);
        assertEquals(5, exporter.getLoopCount(), "Loop count should be settable");

        exporter.setLoopCount(0);
        assertEquals(1, exporter.getLoopCount(), "Loop count should be clamped to minimum 1");

        exporter.setLoopCount(-3);
        assertEquals(1, exporter.getLoopCount(), "Negative loop count should be clamped to 1");
    }

    @Test
    void testExportStereo16Bit() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test-fmt.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1);
        exporter.export(song, wavFile);

        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            // Audio format (PCM=1) at offset 20, LE 16-bit
            raf.seek(20);
            int format = raf.read() | (raf.read() << 8);
            assertEquals(1, format, "Audio format should be PCM (1)");

            // Number of channels at offset 22, LE 16-bit
            int channels = raf.read() | (raf.read() << 8);
            assertEquals(2, channels, "Should be stereo (2 channels)");

            // Skip sample rate (4 bytes) + byte rate (4 bytes) = skip to offset 32
            raf.seek(34);
            int bitsPerSample = raf.read() | (raf.read() << 8);
            assertEquals(16, bitsPerSample, "Should be 16-bit audio");
        }
    }

    @Test
    void testExportedAudioIsNonSilent(@TempDir Path tempDir) throws Exception {
        Song song = createTestSong();
        File output = tempDir.resolve("test.wav").toFile();
        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(1);
        exporter.export(song, output);

        byte[] wav = Files.readAllBytes(output.toPath());
        // Check PCM data after 44-byte header for non-zero samples
        boolean hasNonZero = false;
        for (int i = 44; i < wav.length; i++) {
            if (wav[i] != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "WAV audio data should contain non-zero samples");
    }

    @Test
    void testFadeOutActuallyAttenuates() throws IOException {
        // Use a terminating song so WavExporter's multi-loop mechanism works.
        // A looping (JUMP-based) song never signals completion, so the first
        // WavExporter loop consumes all of maxDurationSeconds leaving no data
        // for the second loop where fade-out is applied.
        Song song = createTerminatingSong();
        File wavFile = new File(tempDir, "test-fade.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(2);
        exporter.setMaxDurationSeconds(30);
        // Use inset mode so the fade is applied over the last fadeDurationSeconds
        // of the rendered PCM. Extend mode would not produce additional audio for
        // terminating songs (the driver is already complete after the loops).
        exporter.setFadeEnabled(true);
        exporter.setFadeExtend(false);
        exporter.setFadeDurationSeconds(3.0);
        exporter.export(song, wavFile);

        byte[] wav = Files.readAllBytes(wavFile.toPath());
        assertTrue(wav.length > 44, "WAV file should have audio data beyond header");

        // Parse PCM data after the 44-byte RIFF header
        int pcmStart = 44;
        int pcmLength = wav.length - pcmStart;
        int sampleCount = pcmLength / 2;
        assertTrue(sampleCount > 0, "Should have PCM samples");

        // Divide into 4 quarters and compute AC RMS (subtract DC mean) of each.
        // The YM2612 discrete chip model adds a constant DC offset (~384) that
        // would mask the fade-out effect if we used raw RMS.
        int samplesPerQuarter = sampleCount / 4;
        double[] acRms = new double[4];
        for (int q = 0; q < 4; q++) {
            int start = pcmStart + q * samplesPerQuarter * 2;

            // First pass: compute mean (DC component)
            long sum = 0;
            int count = 0;
            for (int i = 0; i < samplesPerQuarter; i++) {
                int bytePos = start + i * 2;
                if (bytePos + 1 >= wav.length) break;
                short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
                sum += sample;
                count++;
            }
            double mean = (double) sum / count;

            // Second pass: compute AC RMS (signal with DC removed)
            double sumSquares = 0;
            for (int i = 0; i < samplesPerQuarter; i++) {
                int bytePos = start + i * 2;
                if (bytePos + 1 >= wav.length) break;
                short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
                double ac = sample - mean;
                sumSquares += ac * ac;
            }
            acRms[q] = Math.sqrt(sumSquares / count);
        }

        // With inset fade, the last fadeDurationSeconds of audio get a linear
        // fade from 1.0 to 0.0. The last quarter of the file should be quieter
        // than the first quarter (start of the un-faded first loop).
        assertTrue(acRms[3] < acRms[0],
                "Last quarter AC RMS (" + acRms[3] + ") should be less than first quarter AC RMS ("
                        + acRms[0] + ")");
    }

    @Test
    void testSingleLoopNoFade() throws IOException {
        Song song = createTestSong();
        File wavFile = new File(tempDir, "test-single-loop.wav");

        WavExporter exporter = new WavExporter();
        exporter.setLoopCount(1);
        exporter.setMaxDurationSeconds(2);
        exporter.export(song, wavFile);

        assertTrue(wavFile.exists(), "WAV file should be created");
        byte[] wav = Files.readAllBytes(wavFile.toPath());
        assertTrue(wav.length > 44, "WAV file should have data beyond the 44-byte header");

        // Verify valid WAV header
        assertEquals("RIFF", new String(wav, 0, 4), "Should start with RIFF marker");
        assertEquals("WAVE", new String(wav, 8, 4), "Should contain WAVE marker");

        // With loopCount=1, no fade is applied. Verify that audio data is non-zero.
        boolean hasNonZero = false;
        for (int i = 44; i < wav.length; i++) {
            if (wav[i] != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Single-loop WAV should contain non-zero audio data");
    }

    @Test
    void testExportWithMutedChannels() throws IOException {
        Song song = createAudibleSong();

        // First, export with no muting to get a baseline RMS
        File unmutedFile = new File(tempDir, "test-unmuted.wav");
        WavExporter unmutedExporter = new WavExporter();
        unmutedExporter.setLoopCount(1);
        unmutedExporter.setMaxDurationSeconds(1);
        unmutedExporter.export(song, unmutedFile);

        // Then export with all channels muted
        File mutedFile = new File(tempDir, "test-muted.wav");
        WavExporter mutedExporter = new WavExporter();
        mutedExporter.setLoopCount(1);
        mutedExporter.setMaxDurationSeconds(1);

        // Mute all 10 channels: FM 0-5 and PSG 0-3
        boolean[] allMuted = new boolean[10];
        java.util.Arrays.fill(allMuted, true);
        mutedExporter.setMutedChannels(allMuted);
        mutedExporter.export(song, mutedFile);

        assertTrue(mutedFile.exists(), "WAV file should be created even with all channels muted");
        byte[] mutedWav = Files.readAllBytes(mutedFile.toPath());
        assertTrue(mutedWav.length > 44, "WAV should have data beyond header even when muted");

        // Verify valid WAV header
        assertEquals("RIFF", new String(mutedWav, 0, 4), "Should start with RIFF marker");
        assertEquals("WAVE", new String(mutedWav, 8, 4), "Should contain WAVE marker");

        // Compute AC RMS (DC-removed) for both exports. The YM2612 discrete chip
        // model adds a constant DC offset (~384 after master gain) even when all
        // channels are muted, so raw RMS would not reflect the actual signal level.
        double unmutedAcRms = computeAcRms(Files.readAllBytes(unmutedFile.toPath()));
        double mutedAcRms = computeAcRms(mutedWav);

        // With all channels muted, the AC signal power should be substantially
        // lower than the unmuted export which has an active FM note.
        assertTrue(mutedAcRms < unmutedAcRms,
                "Muted AC RMS (" + mutedAcRms + ") should be less than unmuted AC RMS ("
                        + unmutedAcRms + ")");
    }

    @Test
    void testFadeEnabledFalseProducesCleanOutput() throws IOException {
        Song song = createTerminatingSong();
        File withFade = new File(tempDir, "with-fade.wav");
        File noFade = new File(tempDir, "no-fade.wav");

        WavExporter exporter1 = new WavExporter();
        exporter1.setLoopCount(2);
        exporter1.setMaxDurationSeconds(30);
        exporter1.setFadeEnabled(true);
        exporter1.setFadeDurationSeconds(3.0);
        exporter1.setFadeExtend(false);  // inset mode
        exporter1.export(song, withFade);

        WavExporter exporter2 = new WavExporter();
        exporter2.setLoopCount(2);
        exporter2.setMaxDurationSeconds(30);
        exporter2.setFadeEnabled(false);
        exporter2.export(song, noFade);

        double fadedRms = computeLastQuarterAcRms(Files.readAllBytes(withFade.toPath()));
        double cleanRms = computeLastQuarterAcRms(Files.readAllBytes(noFade.toPath()));
        assertTrue(cleanRms > fadedRms,
                "No-fade last-quarter AC RMS (" + cleanRms + ") should exceed faded ("
                        + fadedRms + ")");
    }

    @Test
    void testExtendModeProducesLongerOutput() throws IOException {
        Song song = createTerminatingSong();
        File extendFile = new File(tempDir, "extend.wav");
        File insetFile = new File(tempDir, "inset.wav");

        WavExporter extendExporter = new WavExporter();
        extendExporter.setLoopCount(2);
        extendExporter.setMaxDurationSeconds(30);
        extendExporter.setFadeEnabled(true);
        extendExporter.setFadeDurationSeconds(2.0);
        extendExporter.setFadeExtend(true);
        extendExporter.export(song, extendFile);

        WavExporter insetExporter = new WavExporter();
        insetExporter.setLoopCount(2);
        insetExporter.setMaxDurationSeconds(30);
        insetExporter.setFadeEnabled(true);
        insetExporter.setFadeDurationSeconds(2.0);
        insetExporter.setFadeExtend(false);
        insetExporter.export(song, insetFile);

        assertTrue(extendFile.length() > insetFile.length(),
                "Extend mode (" + extendFile.length()
                        + ") should produce longer file than inset mode ("
                        + insetFile.length() + ")");
    }

    @Test
    void testHigherLoopCountProducesLongerOutput() throws IOException {
        Song song = createTerminatingSong();
        File oneLoop = new File(tempDir, "one-loop.wav");
        File threeLoops = new File(tempDir, "three-loops.wav");

        WavExporter exporter1 = new WavExporter();
        exporter1.setLoopCount(1);
        exporter1.setMaxDurationSeconds(30);
        exporter1.setFadeEnabled(false);
        exporter1.export(song, oneLoop);

        WavExporter exporter3 = new WavExporter();
        exporter3.setLoopCount(3);
        exporter3.setMaxDurationSeconds(30);
        exporter3.setFadeEnabled(false);
        exporter3.export(song, threeLoops);

        assertTrue(threeLoops.length() > oneLoop.length(),
                "3-loop export (" + threeLoops.length()
                        + ") should be longer than 1-loop export (" + oneLoop.length() + ")");
    }

    @Test
    void testFadeDurationGetterSetter() {
        WavExporter exporter = new WavExporter();
        exporter.setFadeDurationSeconds(5.0);
        assertEquals(5.0, exporter.getFadeDurationSeconds(), 0.001);

        exporter.setFadeDurationSeconds(0.0);
        assertEquals(0.1, exporter.getFadeDurationSeconds(), 0.001,
                "Should clamp to minimum 0.1");

        exporter.setFadeDurationSeconds(-1.0);
        assertEquals(0.1, exporter.getFadeDurationSeconds(), 0.001,
                "Negative values should clamp to 0.1");
    }

    /**
     * Computes AC RMS (DC-removed) of the last quarter of PCM data in a WAV file.
     */
    private double computeLastQuarterAcRms(byte[] wav) {
        int pcmStart = 44;
        int sampleCount = (wav.length - pcmStart) / 2;
        if (sampleCount <= 0) return 0.0;
        int quarterSamples = sampleCount / 4;
        int startByte = pcmStart + (sampleCount - quarterSamples) * 2;

        long sum = 0;
        int count = 0;
        for (int i = 0; i < quarterSamples; i++) {
            int bytePos = startByte + i * 2;
            if (bytePos + 1 >= wav.length) break;
            short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
            sum += sample;
            count++;
        }
        double mean = (double) sum / count;

        double sumSquares = 0;
        for (int i = 0; i < quarterSamples; i++) {
            int bytePos = startByte + i * 2;
            if (bytePos + 1 >= wav.length) break;
            short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
            double ac = sample - mean;
            sumSquares += ac * ac;
        }
        return Math.sqrt(sumSquares / count);
    }

    /**
     * Computes AC RMS (DC-removed root-mean-square) for PCM data in a WAV file.
     * Subtracts the mean sample value before computing RMS, which removes the
     * constant DC offset produced by the YM2612 discrete chip model.
     */
    private double computeAcRms(byte[] wav) {
        int pcmStart = 44;
        int sampleCount = (wav.length - pcmStart) / 2;
        if (sampleCount <= 0) return 0.0;

        // First pass: compute mean (DC component)
        long sum = 0;
        for (int i = 0; i < sampleCount; i++) {
            int bytePos = pcmStart + i * 2;
            if (bytePos + 1 >= wav.length) break;
            short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
            sum += sample;
        }
        double mean = (double) sum / sampleCount;

        // Second pass: compute AC RMS
        double sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            int bytePos = pcmStart + i * 2;
            if (bytePos + 1 >= wav.length) break;
            short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
            double ac = sample - mean;
            sumSquares += ac * ac;
        }
        return Math.sqrt(sumSquares / sampleCount);
    }

    /**
     * Creates a minimal song with one FM channel playing a short note.
     * This produces valid SMPS data via PatternCompiler.
     */
    private Song createTestSong() {
        Song song = new Song();
        song.setTempo(0x80);

        // Simple FM voice: algo 0, op4 as carrier
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x00;   // algo 0, fb 0
        voiceData[2] = 0x7F;   // Op1 TL (silent)
        voiceData[7] = 0x7F;   // Op3 TL (silent)
        voiceData[12] = 0x7F;  // Op2 TL (silent)
        voiceData[16] = 0x01;  // Op4 DT_MUL
        voiceData[17] = 0x00;  // Op4 TL (loud)
        voiceData[18] = 0x1F;  // Op4 RS_AR (AR=31)
        voiceData[21] = 0x0F;  // Op4 D1L_RR (RR=15)
        song.getVoiceBank().add(new FmVoice("Sine", voiceData));

        // Set voice (EF 00), play note C4 (A1) duration 48 (30h)
        addPhrase(song, 0,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA1, 0x30});

        setLoopOnActiveChains(song, 0);
        return song;
    }

    /**
     * Builds an SMPS voice (25 bytes) that produces audible output.
     *
     * <p>SMPS voice layout (operator order: Op1, Op3, Op2, Op4):
     * <pre>
     *   [0]     FB_ALGO
     *   [1-4]   DT_MUL
     *   [5-8]   RS_AR
     *   [9-12]  AM_D1R
     *   [13-16] D2R
     *   [17-20] D1L_RR
     *   [21-24] TL
     * </pre>
     *
     * Uses algorithm 0 (only Op4 is the carrier). Op4 is set to full volume
     * with instant attack (AR=31) and moderate release (RR=15). All modulator
     * operators (Op1, Op2, Op3) are at max attenuation (TL=0x7F).
     */
    private byte[] buildAudibleVoice() {
        byte[] v = new byte[25];
        v[0] = 0x00;           // FB=0, ALGO=0 (Op1->Op2->Op3->Op4, only Op4 is carrier)
        // DT_MUL: [1]=Op1, [2]=Op3, [3]=Op2, [4]=Op4
        v[4] = 0x01;           // Op4 MUL=1 (fundamental frequency)
        // RS_AR: [5]=Op1, [6]=Op3, [7]=Op2, [8]=Op4
        v[8] = 0x1F;           // Op4 AR=31 (fastest attack)
        // AM_D1R: [9]=Op1, [10]=Op3, [11]=Op2, [12]=Op4
        v[12] = 0x00;          // Op4 D1R=0 (no first decay, sustain at max)
        // D2R: [13]=Op1, [14]=Op3, [15]=Op2, [16]=Op4
        v[16] = 0x00;          // Op4 D2R=0 (no second decay)
        // D1L_RR: [17]=Op1, [18]=Op3, [19]=Op2, [20]=Op4
        v[20] = 0x0F;          // Op4 D1L=0 (sustain at max), RR=15 (moderate release)
        // TL: [21]=Op1, [22]=Op3, [23]=Op2, [24]=Op4
        v[21] = 0x7F;          // Op1 TL=0x7F (silent)
        v[22] = 0x7F;          // Op3 TL=0x7F (silent)
        v[23] = 0x7F;          // Op2 TL=0x7F (silent)
        v[24] = 0x00;          // Op4 TL=0x00 (full volume)
        return v;
    }

    /**
     * Creates a song with a properly configured FM voice that produces audible
     * output. Unlike {@link #createTestSong()}, this method uses correct SMPS
     * voice register indices and accounts for the SMPS DAC channel mapping.
     *
     * <p>The SMPS sequencer's FM channel order is {0x16, 0, 1, 2, 4, 5, 6},
     * meaning the first FM pointer in the compiled header always maps to DAC
     * (0x16). To route track data to an actual FM channel, the song must have
     * at least 2 active FM-range channels: channel 0 occupies the DAC slot
     * (with a rest), and channel 1 maps to FM channel 0.
     */
    private Song createAudibleSong() {
        Song song = new Song();
        song.setTempo(0x80);
        song.getVoiceBank().add(new FmVoice("Sine", buildAudibleVoice()));

        // Channel 0: rest (occupies the DAC slot in SMPS header)
        addPhrase(song, 0,
                new byte[]{(byte) 0x80, 0x30});

        // Channel 1: set voice 0, play note C4 (A1) for 48 ticks (maps to FM0)
        addPhrase(song, 1,
                new byte[]{(byte) 0xEF, 0x00, (byte) 0xA1, 0x30});

        setLoopOnActiveChains(song, 0);
        return song;
    }

    /**
     * Creates a song that terminates (hits STOP) rather than looping, with
     * a properly configured FM voice for audible output.
     * <p>
     * The PatternCompiler always appends an F6 (JUMP) at the end of compiled
     * track data, making standard songs loop forever within the SMPS sequencer.
     * WavExporter's multi-loop mechanism relies on the driver reporting
     * {@code isComplete()}, which only happens when all tracks hit F2 (STOP).
     * <p>
     * This method embeds an F2 byte inside the track data (before the trailing
     * position) so the sequencer encounters it before reaching the compiler's
     * appended JUMP. A dummy 0x80 (rest) byte follows the F2 to prevent the
     * compiler from stripping it as a trailing F2.
     */
    private Song createTerminatingSong() {
        Song song = new Song();
        song.setTempo(0x80);
        song.getVoiceBank().add(new FmVoice("Sine", buildAudibleVoice()));

        // Channel 0: rest then STOP (occupies the DAC slot in SMPS header).
        // Both channels must STOP for the sequencer to report isComplete().
        addPhraseRaw(song, 0,
                new byte[]{
                        (byte) 0x80, 0x7F,     // rest, duration 127
                        (byte) 0x80, 0x7F,     // rest, duration 127
                        (byte) 0xF2,           // STOP
                        (byte) 0x80            // dummy trailing byte
                });

        // Channel 1: FM note on FM0 (set voice, play 2x 127 ticks, then STOP)
        addPhraseRaw(song, 1,
                new byte[]{
                        (byte) 0xEF, 0x00,     // set voice 0
                        (byte) 0xA1, 0x7F,     // note C4, duration 127 ticks
                        (byte) 0xA1, 0x7F,     // note C4, duration 127 ticks
                        (byte) 0xF2,           // STOP
                        (byte) 0x80            // dummy trailing byte
                });

        // No loop set -- chains end with F2 STOP so the song terminates
        return song;
    }
}
