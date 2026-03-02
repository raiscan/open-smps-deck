package com.opensmpsdeck.codec;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.io.HexUtil;
import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.*;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsCoordFlags;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic tests for the Drowning theme import-to-playback pipeline.
 * Prints detailed output to help identify where desynchronization occurs.
 */
class TestDrowningPlaybackDiagnostic {

    private File resolveDrowningFile() {
        File smpsFile = new File("docs/SMPS-rips/Sonic The Hedgehog 2/2-1C Drowning.sm2");
        if (!smpsFile.exists()) {
            smpsFile = new File("../docs/SMPS-rips/Sonic The Hedgehog 2/2-1C Drowning.sm2");
        }
        return smpsFile;
    }

    private String channelName(int ch) {
        if (ch == 0) return "FM1";
        if (ch == 1) return "FM2";
        if (ch == 2) return "FM3";
        if (ch == 3) return "FM4";
        if (ch == 4) return "FM5";
        if (ch == 5) return "DAC";
        if (ch == 6) return "PSG1";
        if (ch == 7) return "PSG2";
        if (ch == 8) return "PSG3";
        if (ch == 9) return "NOISE";
        return "CH" + ch;
    }

    @Test
    void diagnoseImportFileVsImportData() throws IOException {
        File file = resolveDrowningFile();
        assertTrue(file.exists(), "Drowning .sm2 file should exist at: " + file.getAbsolutePath());

        System.out.println("========================================================");
        System.out.println("  TEST 1: diagnoseImportFileVsImportData");
        System.out.println("========================================================");
        System.out.println();

        SmpsImporter importer = new SmpsImporter();
        Song songA = importer.importFile(file);
        System.out.println("--- Path A: SmpsImporter.importFile(File) ---");
        printSongSummary(songA);

        byte[] rawData = Files.readAllBytes(file.toPath());
        Song songB = importer.importData(rawData, "Drowning");
        System.out.println("--- Path B: SmpsImporter.importData(byte[], String) ---");
        printSongSummary(songB);

        PatternCompiler compiler = new PatternCompiler();
        byte[] compiledA = compiler.compile(songA);
        byte[] compiledB = compiler.compile(songB);

        System.out.println("--- Compiled Binary Comparison ---");
        System.out.println("Compiled A size: " + compiledA.length + " bytes");
        System.out.println("Compiled B size: " + compiledB.length + " bytes");
        System.out.println();
        System.out.println("Compiled A first 60 bytes:");
        System.out.println("  " + hexDump(compiledA, 0, Math.min(60, compiledA.length)));
        System.out.println("Compiled B first 60 bytes:");
        System.out.println("  " + hexDump(compiledB, 0, Math.min(60, compiledB.length)));

        int minLen = Math.min(compiledA.length, compiledB.length);
        int firstDiff = -1;
        for (int i = 0; i < minLen; i++) {
            if (compiledA[i] != compiledB[i]) { firstDiff = i; break; }
        }
        if (firstDiff >= 0) {
            System.out.println();
            System.out.printf("FIRST DIFFERENCE at offset 0x%04X (%d):%n", firstDiff, firstDiff);
            System.out.printf("  A[0x%04X] = 0x%02X%n", firstDiff, compiledA[firstDiff] & 0xFF);
            System.out.printf("  B[0x%04X] = 0x%02X%n", firstDiff, compiledB[firstDiff] & 0xFF);
            int contextStart = Math.max(0, firstDiff - 8);
            int contextEnd = Math.min(minLen, firstDiff + 8);
            System.out.println("  Context A: " + hexDump(compiledA, contextStart, contextEnd));
            System.out.println("  Context B: " + hexDump(compiledB, contextStart, contextEnd));
        } else if (compiledA.length != compiledB.length) {
            System.out.println();
            System.out.println("DIFFERENCE: Same prefix but different lengths ("
                    + compiledA.length + " vs " + compiledB.length + ")");
        } else {
            System.out.println();
            System.out.println("Compiled binaries are IDENTICAL.");
        }
        System.out.println();
    }

    @Test
    void diagnoseCompiledBinaryIntegrity() throws IOException {
        File file = resolveDrowningFile();
        assertTrue(file.exists(), "Drowning .sm2 file should exist at: " + file.getAbsolutePath());

        System.out.println("========================================================");
        System.out.println("  TEST 2: diagnoseCompiledBinaryIntegrity");
        System.out.println("========================================================");
        System.out.println();

        SmpsImporter importer = new SmpsImporter();
        Song song = importer.importFile(file);
        PatternCompiler compiler = new PatternCompiler();
        byte[] compiled = compiler.compile(song);

        assertNotNull(compiled);
        assertTrue(compiled.length >= 6, "Compiled binary too short: " + compiled.length);

        int voicePtr = readLE16(compiled, 0);
        int fmCount = compiled[2] & 0xFF;
        int psgCount = compiled[3] & 0xFF;
        int dividingTiming = compiled[4] & 0xFF;
        int tempo = compiled[5] & 0xFF;

        System.out.println("--- SMPS Header ---");
        System.out.printf("  voicePtr:       0x%04X (%d)%n", voicePtr, voicePtr);
        System.out.printf("  fmCount:        %d%n", fmCount);
        System.out.printf("  psgCount:       %d%n", psgCount);
        System.out.printf("  dividingTiming: 0x%02X (%d)%n", dividingTiming, dividingTiming);
        System.out.printf("  tempo:          0x%02X (%d)%n", tempo, tempo);
        System.out.println();

        int offset = 6;

        System.out.println("--- FM Channel Entries ---");
        int[] fmPointers = new int[fmCount];
        int[] fmKeys = new int[fmCount];
        int[] fmVols = new int[fmCount];
        for (int i = 0; i < fmCount; i++) {
            if (offset + 3 >= compiled.length) { System.out.printf("  FM[%d]: TRUNCATED%n", i); break; }
            fmPointers[i] = readLE16(compiled, offset);
            fmKeys[i] = compiled[offset + 2];
            fmVols[i] = compiled[offset + 3];
            System.out.printf("  FM[%d]: ptr=0x%04X key=%d vol=%d%n", i, fmPointers[i], fmKeys[i], fmVols[i]);
            offset += 4;
        }
        System.out.println();

        System.out.println("--- PSG Channel Entries ---");
        int[] psgPointers = new int[psgCount];
        int[] psgKeys = new int[psgCount];
        int[] psgVols = new int[psgCount];
        int[] psgMods = new int[psgCount];
        int[] psgInstruments = new int[psgCount];
        for (int i = 0; i < psgCount; i++) {
            if (offset + 5 >= compiled.length) { System.out.printf("  PSG[%d]: TRUNCATED%n", i); break; }
            psgPointers[i] = readLE16(compiled, offset);
            psgKeys[i] = compiled[offset + 2];
            psgVols[i] = compiled[offset + 3];
            psgMods[i] = compiled[offset + 4] & 0xFF;
            psgInstruments[i] = compiled[offset + 5] & 0xFF;
            System.out.printf("  PSG[%d]: ptr=0x%04X key=%d vol=%d mod=0x%02X inst=%d%n",
                    i, psgPointers[i], psgKeys[i], psgVols[i], psgMods[i], psgInstruments[i]);
            offset += 6;
        }
        System.out.println();

        int headerEnd = offset;
        System.out.printf("  Header ends at offset 0x%04X (%d)%n", headerEnd, headerEnd);
        System.out.println();

        System.out.println("--- Channel Track Data Validation ---");
        for (int i = 0; i < fmCount; i++) {
            int ptr = fmPointers[i];
            boolean valid = ptr >= 0 && ptr < compiled.length;
            System.out.printf("  FM[%d] @ 0x%04X: %s%n", i, ptr, valid ? "VALID" : "OUT OF BOUNDS");
            if (valid) {
                int end = Math.min(ptr + 10, compiled.length);
                System.out.printf("    First bytes: %s%n", hexDump(compiled, ptr, end));
                int firstByte = compiled[ptr] & 0xFF;
                System.out.printf("    First byte 0x%02X: %s%n", firstByte, classifyByte(firstByte));
            }
        }
        for (int i = 0; i < psgCount; i++) {
            int ptr = psgPointers[i];
            boolean valid = ptr >= 0 && ptr < compiled.length;
            System.out.printf("  PSG[%d] @ 0x%04X: %s%n", i, ptr, valid ? "VALID" : "OUT OF BOUNDS");
            if (valid) {
                int end = Math.min(ptr + 10, compiled.length);
                System.out.printf("    First bytes: %s%n", hexDump(compiled, ptr, end));
                int firstByte = compiled[ptr] & 0xFF;
                System.out.printf("    First byte 0x%02X: %s%n", firstByte, classifyByte(firstByte));
            }
        }

        System.out.println();
        if (voicePtr > 0 && voicePtr < compiled.length) {
            int voiceBytes = compiled.length - voicePtr;
            int voiceCount = voiceBytes / FmVoice.VOICE_SIZE;
            System.out.printf("--- Voice Table @ 0x%04X: %d bytes, ~%d voices ---%n",
                    voicePtr, voiceBytes, voiceCount);
        } else {
            System.out.println("--- No voice table (voicePtr=0x"
                    + Integer.toHexString(voicePtr) + ") ---");
        }
        System.out.println();
    }

    @Test
    void diagnosePlaybackEnginePath() throws IOException {
        File file = resolveDrowningFile();
        assertTrue(file.exists(), "Drowning .sm2 file should exist at: " + file.getAbsolutePath());

        System.out.println("========================================================");
        System.out.println("  TEST 3: diagnosePlaybackEnginePath");
        System.out.println("========================================================");
        System.out.println();

        SmpsImporter importer = new SmpsImporter();
        Song song = importer.importFile(file);

        System.out.println("Song imported: " + song.getName());
        System.out.println("SmpsMode: " + song.getSmpsMode());
        System.out.println("Tempo: 0x" + Integer.toHexString(song.getTempo()).toUpperCase());
        System.out.println("DividingTiming: " + song.getDividingTiming());
        System.out.println();

        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);
        SmpsDriver driver = engine.getDriver();

        int sampleRate = 44100;
        int stereoSamplesPerSecond = sampleRate * 2;
        boolean anyNonZero = false;
        System.out.println("--- Rendering 5 seconds of audio ---");

        for (int sec = 0; sec < 5; sec++) {
            long nonZeroCount = 0;
            long maxAmplitude = 0;
            double rms = 0;
            int chunkSize = 8820;
            short[] chunk = new short[chunkSize];
            int samplesRendered = 0;

            while (samplesRendered < stereoSamplesPerSecond) {
                int toRender = Math.min(chunkSize, stereoSamplesPerSecond - samplesRendered);
                short[] renderBuf = (toRender == chunkSize) ? chunk : new short[toRender];
                engine.renderBuffer(renderBuf);
                for (int i = 0; i < renderBuf.length; i++) {
                    int abs = Math.abs(renderBuf[i]);
                    if (abs > 0) nonZeroCount++;
                    if (abs > maxAmplitude) maxAmplitude = abs;
                    rms += (double) renderBuf[i] * renderBuf[i];
                }
                samplesRendered += toRender;
            }

            rms = Math.sqrt(rms / stereoSamplesPerSecond);
            if (nonZeroCount > 0) anyNonZero = true;

            StringBuilder trackPositions = new StringBuilder();
            for (int fm = 0; fm < 6; fm++) {
                int pos = driver.getTrackPosition(SmpsSequencer.TrackType.FM, fm);
                if (pos >= 0) trackPositions.append(String.format("FM%d=0x%04X ", fm, pos));
            }
            for (int psg = 0; psg < 4; psg++) {
                int pos = driver.getTrackPosition(SmpsSequencer.TrackType.PSG, psg);
                if (pos >= 0) trackPositions.append(String.format("PSG%d=0x%04X ", psg, pos));
            }
            int dacPos = driver.getTrackPosition(SmpsSequencer.TrackType.DAC, 5);
            if (dacPos >= 0) trackPositions.append(String.format("DAC=0x%04X ", dacPos));

            System.out.printf("  Second %d: nonZero=%d/%d maxAmp=%d RMS=%.1f complete=%s%n",
                    sec + 1, nonZeroCount, stereoSamplesPerSecond,
                    maxAmplitude, rms, driver.isComplete());
            System.out.printf("    Track positions: %s%n", trackPositions);
        }

        System.out.println();
        assertTrue(anyNonZero, "Playback should produce non-zero audio output");
        assertFalse(driver.isComplete(),
                "Driver should not be complete after 5 seconds for a looping song");
        System.out.println("RESULT: Playback engine produces audio successfully.");
        System.out.println();
    }

    // Helper methods
    private void printSongSummary(Song song) {
        System.out.println("  Name: " + song.getName());
        System.out.println("  SmpsMode: " + song.getSmpsMode());
        System.out.println("  ArrangementMode: " + song.getArrangementMode());
        System.out.println("  Tempo: 0x" + Integer.toHexString(song.getTempo()).toUpperCase()
                + " (" + song.getTempo() + ")");
        System.out.println("  DividingTiming: " + song.getDividingTiming());
        System.out.println("  Voices: " + song.getVoiceBank().size());
        System.out.println("  DAC samples: " + song.getDacSamples().size());
        System.out.println("  PSG envelopes: " + song.getPsgEnvelopes().size());
        HierarchicalArrangement hier = song.getHierarchicalArrangement();
        PhraseLibrary library = hier.getPhraseLibrary();
        System.out.println("  PhraseLibrary size: " + library.getAllPhrases().size());
        System.out.println("  Channels with non-empty chains:");
        for (int ch = 0; ch < 10; ch++) {
            Chain chain = hier.getChain(ch);
            if (!chain.getEntries().isEmpty()) {
                System.out.printf("    Ch%d (%s): %d entries, loop=%d%n",
                        ch, channelName(ch), chain.getEntries().size(),
                        chain.getLoopEntryIndex());
            }
        }
        System.out.println();
    }

    private String hexDump(byte[] data, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < data.length; i++) {
            if (i > from) sb.append((char) 32);
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private String classifyByte(int b) {
        if (b == 0x80) return "REST";
        if (b >= 0x81 && b <= 0xDF) return "NOTE (0x" + Integer.toHexString(b).toUpperCase() + ")";
        if (b >= 0x01 && b <= 0x7F) return "DURATION (" + b + " frames)";
        if (b >= 0xE0 && b <= 0xFF) {
            String label = SmpsCoordFlags.getLabel(b);
            return "COORD FLAG: " + (label != null ? label : "0x" + Integer.toHexString(b).toUpperCase());
        }
        if (b == 0x00) return "ZERO (possibly padding or error)";
        return "UNKNOWN";
    }

    private int readLE16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
