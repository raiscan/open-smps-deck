package com.opensmpsdeck.codec;

import com.opensmpsdeck.io.HexUtil;
import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestDrowningTempoImport {

    private static final int[] EXPECTED_TEMPOS = {0xAB, 0xC0, 0xD6, 0xE7};
    private static final int TEMPO_CHANNEL_INDEX = 1;

    private Path resolveDrowningFile() {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path smpsFile = projectRoot.resolve(
                "docs/SMPS-rips/Sonic The Hedgehog 2/2-1C Drowning.sm2");
        if (!Files.exists(smpsFile)) {
            smpsFile = projectRoot.getParent().resolve(
                    "docs/SMPS-rips/Sonic The Hedgehog 2/2-1C Drowning.sm2");
        }
        return smpsFile;
    }

    @Test
    void rawFileContainsSetTempoBytes() throws IOException {
        Path file = resolveDrowningFile();
        assertTrue(Files.exists(file), "Drowning .sm2 file should exist at: " + file);
        byte[] raw = Files.readAllBytes(file);
        List<Integer> eaOffsets = findCoordFlagOccurrences(raw, SmpsCoordFlags.SET_TEMPO);
        assertFalse(eaOffsets.isEmpty(), "Raw .sm2 file should contain at least one EA (SET_TEMPO) byte");
        assertEquals(EXPECTED_TEMPOS.length, eaOffsets.size(),
                "Raw file should contain exactly " + EXPECTED_TEMPOS.length + " SET_TEMPO commands, found " + eaOffsets.size());
        for (int i = 0; i < eaOffsets.size(); i++) {
            int offset = eaOffsets.get(i);
            int param = raw[offset + 1] & 0xFF;
            assertEquals(EXPECTED_TEMPOS[i], param,
                    String.format("SET_TEMPO #%d at offset 0x%04X: expected param 0x%02X, got 0x%02X",
                            i, offset, EXPECTED_TEMPOS[i], param));
        }
    }

    @Test
    void importedPhrasesContainSetTempoBytes() throws IOException {
        Path file = resolveDrowningFile();
        assertTrue(Files.exists(file), "Drowning .sm2 file should exist");
        byte[] raw = Files.readAllBytes(file);
        Song song = new SmpsImporter().importData(raw, "Drowning");
        assertNotNull(song, "Imported song should not be null");
        assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode(),
                "Imported song should use hierarchical arrangement");
        HierarchicalArrangement hier = song.getHierarchicalArrangement();
        PhraseLibrary library = hier.getPhraseLibrary();
        List<TempoOccurrence> allTempos = new ArrayList<>();
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            Chain chain = hier.getChain(ch);
            if (chain.getEntries().isEmpty()) continue;
            for (int entryIdx = 0; entryIdx < chain.getEntries().size(); entryIdx++) {
                ChainEntry entry = chain.getEntries().get(entryIdx);
                Phrase phrase = library.getPhrase(entry.getPhraseId());
                if (phrase == null) continue;
                byte[] phraseData = phrase.getDataDirect();
                List<Integer> eaPositions = findCoordFlagPositionsInBytecode(
                        phraseData, SmpsCoordFlags.SET_TEMPO);
                for (int pos : eaPositions) {
                    int param = phraseData[pos + 1] & 0xFF;
                    allTempos.add(new TempoOccurrence(ch, entryIdx, phrase.getId(),
                            phrase.getName(), pos, param));
                }
            }
        }
        System.out.println("=== Imported Phrase Tempo Analysis ===");
        System.out.println("Total phrases in library: " + library.getAllPhrases().size());
        System.out.println("SET_TEMPO occurrences found in phrases: " + allTempos.size());
        for (var t : allTempos) {
            System.out.printf("  Ch%d entry%d phrase[%d] offset %d: EA %02X%n",
                    t.channel, t.entryIndex, t.phraseId, t.byteOffset, t.tempoValue);
        }
        System.out.println();
        System.out.println("=== Channel " + TEMPO_CHANNEL_INDEX + " chain entries ===");
        Chain tempoChain = hier.getChain(TEMPO_CHANNEL_INDEX);
        for (int i = 0; i < tempoChain.getEntries().size(); i++) {
            ChainEntry entry = tempoChain.getEntries().get(i);
            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) {
                System.out.printf("  Entry %d: phrase %d (NOT FOUND)%n", i, entry.getPhraseId());
                continue;
            }
            System.out.printf("  Entry %d: phrase %d [%s] repeat=%d transpose=%d%n",
                    i, phrase.getId(), HexUtil.bytesToHex(phrase.getDataDirect()),
                    entry.getRepeatCount(), entry.getTransposeSemitones());
        }
        assertFalse(allTempos.isEmpty(),
                "Imported phrases should contain SET_TEMPO (EA) commands. "
                        + "If empty, the decompiler is stripping them during import.");
        List<Integer> foundParams = allTempos.stream().map(t -> t.tempoValue).toList();
        for (int expected : EXPECTED_TEMPOS) {
            assertTrue(foundParams.contains(expected),
                    String.format("Expected SET_TEMPO param 0x%02X not found in imported phrases. "
                            + "Found params: %s", expected,
                            foundParams.stream().map(v -> String.format("0x%02X", v)).toList()));
        }
    }

    @Test
    void recompiledBinaryContainsSetTempoBytes() throws IOException {
        Path file = resolveDrowningFile();
        assertTrue(Files.exists(file), "Drowning .sm2 file should exist");
        byte[] raw = Files.readAllBytes(file);
        Song song = new SmpsImporter().importData(raw, "Drowning");
        PatternCompiler compiler = new PatternCompiler();
        byte[] compiled = compiler.compile(song);
        assertNotNull(compiled, "Compiled SMPS data should not be null");
        assertTrue(compiled.length > 0, "Compiled SMPS data should not be empty");
        List<int[]> compiledTempos = new ArrayList<>();
        int fmCount = compiled[2] & 0xFF;
        int psgCount = compiled[3] & 0xFF;
        int headerEnd = 6 + fmCount * 4 + psgCount * 6;
        int pos = headerEnd;
        while (pos < compiled.length - 1) {
            int b = compiled[pos] & 0xFF;
            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if (b == SmpsCoordFlags.SET_TEMPO && pos + 1 < compiled.length) {
                    compiledTempos.add(new int[]{pos, compiled[pos + 1] & 0xFF});
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
        System.out.println("=== Compiled Binary Tempo Analysis ===");
        System.out.println("Compiled binary size: " + compiled.length + " bytes");
        System.out.println("SET_TEMPO occurrences in compiled binary: " + compiledTempos.size());
        for (int[] t : compiledTempos) {
            int ctxStart = Math.max(0, t[0] - 4);
            int ctxEnd = Math.min(compiled.length, t[0] + 4);
            byte[] context = new byte[ctxEnd - ctxStart];
            System.arraycopy(compiled, ctxStart, context, 0, context.length);
            System.out.printf("  Offset 0x%04X: EA %02X (context: %s)%n",
                    t[0], t[1], HexUtil.bytesToHex(context));
        }
        assertFalse(compiledTempos.isEmpty(),
                "Recompiled binary should contain SET_TEMPO (EA) commands. "
                        + "If empty, the compiler is dropping them during recompilation.");
        List<Integer> compiledParams = compiledTempos.stream().map(t -> t[1]).toList();
        for (int expected : EXPECTED_TEMPOS) {
            assertTrue(compiledParams.contains(expected),
                    String.format("Expected SET_TEMPO param 0x%02X not found in compiled binary. "
                            + "Found params: %s", expected,
                            compiledParams.stream().map(v -> String.format("0x%02X", v)).toList()));
        }
    }

    @Test
    void diagnosePipelineTempoPreservation() throws IOException {
        Path file = resolveDrowningFile();
        assertTrue(Files.exists(file), "Drowning .sm2 file should exist");
        byte[] raw = Files.readAllBytes(file);
        int rawEaCount = findCoordFlagOccurrences(raw, SmpsCoordFlags.SET_TEMPO).size();
        System.out.println("=== Pipeline Diagnosis ===");
        System.out.println("Stage 1 - Raw file:       " + rawEaCount + " SET_TEMPO commands");
        Song song = new SmpsImporter().importData(raw, "Drowning");
        int phraseEaCount = 0;
        HierarchicalArrangement hier = song.getHierarchicalArrangement();
        PhraseLibrary library = hier.getPhraseLibrary();
        for (Phrase phrase : library.getAllPhrases()) {
            phraseEaCount += findCoordFlagPositionsInBytecode(
                    phrase.getDataDirect(), SmpsCoordFlags.SET_TEMPO).size();
        }
        System.out.println("Stage 2 - After import:   " + phraseEaCount
                + " SET_TEMPO commands in phrases");
        byte[] compiled = new PatternCompiler().compile(song);
        int compiledEaCount = 0;
        int pos = 6 + (compiled[2] & 0xFF) * 4 + (compiled[3] & 0xFF) * 6;
        while (pos < compiled.length - 1) {
            int b = compiled[pos] & 0xFF;
            if (b >= 0xE0) {
                if (b == SmpsCoordFlags.SET_TEMPO) compiledEaCount++;
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else {
                pos++;
            }
        }
        System.out.println("Stage 3 - After compile:  " + compiledEaCount
                + " SET_TEMPO commands in binary");
        if (rawEaCount > 0 && phraseEaCount == 0) {
            System.out.println();
            System.out.println("DIAGNOSIS: Import/decompile step loses SET_TEMPO commands.");
            System.out.println("The HierarchyDecompiler or SmpsImporter is stripping EA bytes.");
        } else if (phraseEaCount > 0 && compiledEaCount == 0) {
            System.out.println();
            System.out.println("DIAGNOSIS: Compile step loses SET_TEMPO commands.");
            System.out.println("The HierarchyCompiler or PatternCompiler is stripping EA bytes.");
        } else if (rawEaCount > 0 && phraseEaCount > 0 && compiledEaCount > 0) {
            System.out.println();
            System.out.println("DIAGNOSIS: All stages preserve SET_TEMPO commands correctly.");
        }
        assertEquals(rawEaCount, compiledEaCount,
                "The number of SET_TEMPO commands should be preserved through "
                        + "the full import-compile pipeline. Raw=" + rawEaCount
                        + " Compiled=" + compiledEaCount);
    }


    @Test
    void playbackActuallyChangesTempoForDrowning() throws IOException {
        Path file = resolveDrowningFile();
        assertTrue(Files.exists(file), "Drowning .sm2 file should exist at: " + file);

        // Stage 1: Import the Drowning .sm2 file
        byte[] raw = Files.readAllBytes(file);
        Song song = new SmpsImporter().importData(raw, "Drowning");
        assertNotNull(song, "Imported song should not be null");

        // Stage 2: Compile using PatternCompiler
        PatternCompiler compiler = new PatternCompiler();
        byte[] compiled = compiler.compile(song);
        assertNotNull(compiled, "Compiled SMPS data should not be null");
        assertTrue(compiled.length > 0, "Compiled SMPS data should not be empty");

        // Stage 3: Wrap as SimpleSmpsData (baseNoteOffset = 1 for S2)
        SimpleSmpsData data = new SimpleSmpsData(compiled, 1);

        // Stage 4: Build S2 SmpsSequencerConfig
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{0x16, 0, 1, 2, 4, 5, 6})
                .psgChannelOrder(new int[]{0x80, 0xA0, 0xC0})
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();

        // Stage 5: Create SmpsDriver and SmpsSequencer
        SmpsDriver driver = new SmpsDriver(44100.0);
        SmpsSequencer sequencer = new SmpsSequencer(data, null, driver, config);

        // Stage 6: Wire sequencer into driver
        driver.addSequencer(sequencer, false);

        // Stage 7: Record initial tempo
        int initialTempo = sequencer.getNormalTempo();
        System.out.println("=== Drowning Playback Tempo Test ===");
        System.out.println("Initial normalTempo: 0x" + Integer.toHexString(initialTempo).toUpperCase());

        // Stage 8: Render audio in a loop, checking for tempo change
        short[] buffer = new short[8820]; // ~100ms of stereo audio at 44100 Hz
        boolean tempoChanged = false;
        int finalTempo = initialTempo;
        int changeIteration = -1;
        for (int i = 0; i < 100; i++) { // up to ~10 seconds of audio
            driver.read(buffer);
            int currentTempo = sequencer.getNormalTempo();
            if (currentTempo != initialTempo) {
                tempoChanged = true;
                finalTempo = currentTempo;
                changeIteration = i;
                System.out.println("Tempo changed at iteration " + i + " (~"
                        + (i * 100) + "ms): 0x"
                        + Integer.toHexString(currentTempo).toUpperCase());
                break;
            }
        }

        // Stage 9: Report final state
        if (!tempoChanged) {
            finalTempo = sequencer.getNormalTempo();
            System.out.println("After 100 iterations (~10s), normalTempo is still: 0x"
                    + Integer.toHexString(finalTempo).toUpperCase());
        }

        // Stage 10: Assert that the tempo actually changed during playback
        assertTrue(tempoChanged,
                "Drowning theme should change tempo during playback. "
                        + "Initial tempo was 0x" + Integer.toHexString(initialTempo).toUpperCase()
                        + ", final tempo is 0x" + Integer.toHexString(finalTempo).toUpperCase()
                        + ". If this fails, SET_TEMPO (EA) commands are not being executed by the sequencer.");
    }
    private List<Integer> findCoordFlagOccurrences(byte[] data, int flagByte) {
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == flagByte) {
                offsets.add(i);
            }
        }
        return offsets;
    }

    private List<Integer> findCoordFlagPositionsInBytecode(byte[] data, int flagByte) {
        List<Integer> positions = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (b >= 0xE0) {
                if (b == flagByte) {
                    positions.add(pos);
                }
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else {
                pos++;
            }
        }
        return positions;
    }

    private record TempoOccurrence(
            int channel, int entryIndex, int phraseId, String phraseName,
            int byteOffset, int tempoValue) {}
}
