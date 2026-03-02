package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.HierarchyDecompiler;
import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Imports raw SMPS binary files into Song models.
 * Works with SMPSPlay .bin/.sm2/.s3k/.smp rips and exported files.
 *
 * <p>Handles Z80-absolute pointers found in SM2/S3K rips by computing
 * a SeqBase offset (either from the filename or by auto-detection).
 * When importing from a file, companion DAC samples ({@code DAC.ini} +
 * {@code DefDrum.txt}) and PSG envelopes ({@code PSG.lst}) are loaded
 * from the parent directory if present.
 *
 * <p>Uses {@link SmpsCoordFlags} for all coordination flag parameter counts
 * to ensure correct bytecode parsing aligned with the Z80 driver.
 */
public class SmpsImporter {

    private static final int FM_CHANNEL_COUNT = 6;
    private static final int PSG_CHANNEL_COUNT = 4;

    /** DPCM delta table used by the Z80 DAC driver for sample decompression. */
    static final int[] DPCM_DELTA_TABLE = {
        0, 1, 2, 4, 8, 16, 32, 64,
        -128, -1, -2, -4, -8, -16, -32, -64
    };

    /**
     * Import an SMPS binary file as a Song.
     * Detects SmpsMode from extension, resolves SeqBase, and loads companion files.
     */
    public Song importFile(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        String filename = file.getName();
        String lowerName = filename.toLowerCase();

        // Detect SmpsMode from extension
        SmpsMode mode = SmpsMode.S2;
        String name = filename;
        for (String ext : new String[]{".sm2", ".s3k", ".smp", ".bin"}) {
            if (lowerName.endsWith(ext)) {
                name = filename.substring(0, filename.length() - ext.length());
                mode = switch (ext) {
                    case ".s3k" -> SmpsMode.S3K;
                    case ".smp" -> SmpsMode.S1;
                    default -> SmpsMode.S2;
                };
                break;
            }
        }

        // Try filename-encoded SeqBase (e.g., "song.1380.sm2")
        int seqBase = parseFilenameOffset(filename);

        Song song = importData(data, name, seqBase, mode);
        song.setSmpsMode(mode);

        // Load companion files from parent directory
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            loadDacSamples(parentDir, song);
            loadPsgEnvelopes(parentDir, song);
        }

        return song;
    }

    /**
     * Import raw SMPS binary data as a Song.
     * Auto-detects SeqBase from pointer analysis.
     */
    public Song importData(byte[] data, String name) {
        return importData(data, name, -1, SmpsMode.S2);
    }

    /**
     * Import raw SMPS binary data with an explicit SeqBase.
     *
     * @param seqBase Z80 RAM base offset to subtract from pointers, or -1 to auto-detect
     */
    Song importData(byte[] data, String name, int seqBase, SmpsMode mode) {
        if (data.length < 6) {
            throw new IllegalArgumentException("SMPS data too short: " + data.length + " bytes");
        }

        Song song = new Song();
        song.getPatterns().clear();
        song.getOrderList().clear();
        song.setName(name != null ? name.replaceAll("\\.[^.]+$", "") : "Imported");

        // Parse header (raw pointers before SeqBase adjustment)
        int voicePtr = readLE16(data, 0);
        int fmCount = data[2] & 0xFF;
        int psgCount = data[3] & 0xFF;
        song.setDividingTiming(data[4] & 0xFF);
        song.setTempo(data[5] & 0xFF);

        // Parse FM channel entries (raw pointers)
        int offset = 6;
        int[] fmPointers = new int[fmCount];
        int[] fmKeys = new int[fmCount];
        int[] fmVols = new int[fmCount];
        for (int i = 0; i < fmCount; i++) {
            if (offset + 3 >= data.length) break;
            fmPointers[i] = readLE16(data, offset);
            fmKeys[i] = (byte) data[offset + 2];
            fmVols[i] = (byte) data[offset + 3];
            offset += 4;
        }

        // Parse PSG channel entries (raw pointers)
        int[] psgPointers = new int[psgCount];
        int[] psgKeys = new int[psgCount];
        int[] psgVols = new int[psgCount];
        int[] psgMods = new int[psgCount];
        int[] psgInstruments = new int[psgCount];
        for (int i = 0; i < psgCount; i++) {
            if (offset + 5 >= data.length) break;
            psgPointers[i] = readLE16(data, offset);
            psgKeys[i] = (byte) data[offset + 2];
            psgVols[i] = (byte) data[offset + 3];
            psgMods[i] = data[offset + 4] & 0xFF;
            psgInstruments[i] = data[offset + 5] & 0xFF;
            offset += 6;
        }

        // Auto-detect SeqBase if not provided
        if (seqBase < 0) {
            seqBase = guessSeqBase(data, fmCount, psgCount, voicePtr, fmPointers, psgPointers);
        }

        // Apply SeqBase to all pointers
        voicePtr -= seqBase;
        for (int i = 0; i < fmCount; i++) {
            fmPointers[i] -= seqBase;
        }
        for (int i = 0; i < psgCount; i++) {
            psgPointers[i] -= seqBase;
        }

        // Extract voices from voice table
        if (voicePtr > 0 && voicePtr < data.length) {
            int voiceCount = estimateVoiceCount(data, voicePtr, fmPointers, psgPointers);
            for (int i = 0; i < voiceCount; i++) {
                int vOffset = voicePtr + i * FmVoice.VOICE_SIZE;
                if (vOffset + FmVoice.VOICE_SIZE > data.length) break;
                byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
                System.arraycopy(data, vOffset, voiceData, 0, FmVoice.VOICE_SIZE);
                song.getVoiceBank().add(new FmVoice("Voice " + i, voiceData));
            }
        }

        // Create a single pattern with all track data
        Pattern pattern = new Pattern(0, 64);
        // Parallel array: normalized data with JUMP intact for hierarchical decompilation
        byte[][] decompileData = new byte[Pattern.CHANNEL_COUNT][];
        // Per-channel PSG header values for hierarchical phrase initialization
        int[] psgHeaderInstrument = new int[Pattern.CHANNEL_COUNT];
        int[] psgHeaderKey = new int[Pattern.CHANNEL_COUNT];
        int[] psgHeaderVol = new int[Pattern.CHANNEL_COUNT];

        // Extract FM track data
        for (int i = 0; i < fmCount && i < FM_CHANNEL_COUNT; i++) {
            int ptr = fmPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                byte[] trackData = extractReachableTrackData(data, ptr, seqBase);
                if (trackData.length > 0) {
                    trackData = normalizeTrackPointers(trackData, ptr, seqBase);
                    decompileData[i] = trackData;
                    trackData = stripJumpTerminator(trackData);
                    trackData = prependFmHeaderState(trackData, fmKeys[i], fmVols[i]);
                    pattern.setTrackData(i, trackData);
                }
            }
        }

        // Extract PSG track data
        for (int i = 0; i < psgCount && i < PSG_CHANNEL_COUNT; i++) {
            int ptr = psgPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                byte[] trackData = extractReachableTrackData(data, ptr, seqBase);
                if (trackData.length > 0) {
                    trackData = normalizeTrackPointers(trackData, ptr, seqBase);
                    // Detect PSG noise mode: if track contains F3 flag, map to Noise channel (9)
                    int channelIndex = FM_CHANNEL_COUNT + i;
                    if (containsPsgNoiseFlag(trackData)) {
                        channelIndex = FM_CHANNEL_COUNT + 3; // PSG Noise = channel 9
                    }
                    decompileData[channelIndex] = trackData;
                    psgHeaderInstrument[channelIndex] = psgInstruments[i];
                    psgHeaderKey[channelIndex] = psgKeys[i];
                    psgHeaderVol[channelIndex] = psgVols[i];
                    trackData = stripJumpTerminator(trackData);
                    trackData = prependPsgHeaderState(
                            trackData, psgKeys[i], psgVols[i], psgInstruments[i], psgMods[i]);
                    pattern.setTrackData(channelIndex, trackData);
                }
            }
        }

        song.getPatterns().add(pattern);

        // Single order row pointing to pattern 0
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        song.getOrderList().add(orderRow);
        song.setLoopPoint(0);

        // Populate hierarchical arrangement from extracted track data
        int noteCompensation = switch (mode) {
            case S1, S3K -> -1;
            case S2 -> 0;
        };
        HierarchicalArrangement hier = song.getHierarchicalArrangement();
        PhraseLibrary library = hier.getPhraseLibrary();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            byte[] trackData = decompileData[ch];
            if (trackData == null || trackData.length == 0) continue;

            // Reverse note compensation for S1/S3K FM channels only (0-4).
            // PSG (6-9) uses baseNoteOffset=0 in all modes, no compensation needed.
            if (noteCompensation != 0 && ch < 5) { // FM channels only
                trackData = applyNoteCompensation(trackData, noteCompensation);
            }

            ChannelType channelType = ChannelType.fromChannelIndex(ch);
            HierarchyDecompiler.DecompileResult result =
                    HierarchyDecompiler.decompileTrack(trackData, channelType);

            // Remap phrase IDs from local decompiler library to global song library
            Map<Integer, Integer> idMap = new HashMap<>();
            boolean firstPhrase = true;
            for (Phrase localPhrase : result.phrases()) {
                Phrase newPhrase = library.createPhrase(localPhrase.getName(), localPhrase.getChannelType());
                byte[] phraseData = localPhrase.getDataDirect();

                // Prepend PSG header initialization (instrument, key offset, volume)
                // to the first phrase so it's self-contained. The SMPS header stores
                // these per-channel but the compiler writes zeros, so without this
                // the PSG envelope/volume would be wrong after the round-trip.
                if (firstPhrase && ch >= FM_CHANNEL_COUNT) {
                    phraseData = prependPsgInitToPhrase(phraseData,
                        psgHeaderKey[ch], psgHeaderVol[ch], psgHeaderInstrument[ch]);
                    firstPhrase = false;
                }

                newPhrase.setData(phraseData);
                idMap.put(localPhrase.getId(), newPhrase.getId());
            }

            // Add remapped chain entries
            Chain chain = hier.getChain(ch);
            for (ChainEntry localEntry : result.chainEntries()) {
                Integer newId = idMap.get(localEntry.getPhraseId());
                if (newId == null) continue;
                ChainEntry newEntry = new ChainEntry(newId);
                newEntry.setTransposeSemitones(localEntry.getTransposeSemitones());
                newEntry.setRepeatCount(localEntry.getRepeatCount());
                chain.getEntries().add(newEntry);
            }

            // Set loop point
            if (result.hasLoopPoint() && result.loopEntryIndex() >= 0) {
                chain.setLoopEntryIndex(result.loopEntryIndex());
            }
        }
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        return song;
    }

    /**
     * Estimate how many 25-byte voices fit in the voice table region.
     * Uses knowledge of track pointers to bound the voice region where possible.
     */
    private int estimateVoiceCount(byte[] data, int voicePtr, int[] fmPointers, int[] psgPointers) {
        // Find the smallest track pointer that falls after the voice pointer,
        // which would indicate where the voice table ends
        int boundary = data.length;
        for (int ptr : fmPointers) {
            if (ptr > voicePtr && ptr < boundary) {
                boundary = ptr;
            }
        }
        for (int ptr : psgPointers) {
            if (ptr > voicePtr && ptr < boundary) {
                boundary = ptr;
            }
        }

        int available = boundary - voicePtr;
        int maxVoices = available / FmVoice.VOICE_SIZE;
        // Cap at reasonable maximum
        return Math.min(maxVoices, 64);
    }

    /**
     * Find the end of a track by scanning for track terminators.
     * Uses {@link SmpsCoordFlags} for correct flag identification:
     * F2 = Stop, F6 = Jump (both terminate the track).
     * Returns the offset AFTER the terminal command and its parameters.
     */
    private int findTrackEnd(byte[] data, int start) {
        int pos = start;
        while (pos < data.length) {
            int cmd = data[pos] & 0xFF;

            if (cmd == SmpsCoordFlags.STOP) {
                return pos + 1; // F2 = track end, include it
            }
            if (cmd == SmpsCoordFlags.JUMP) {
                return pos + 1 + SmpsCoordFlags.getParamCount(cmd); // F6 + 2-byte pointer
            }

            // Skip coordination flags with parameters
            if (cmd >= 0xE0 && cmd <= 0xFF) {
                pos++; // skip the flag byte
                pos += SmpsCoordFlags.getParamCount(cmd);
                continue;
            }

            // Note or duration byte
            pos++;
        }
        return data.length;
    }

    /**
     * Parse a filename-encoded SeqBase offset.
     * Looks for a 4-digit hex value between the last two dots before the extension.
     * For example, "song.1380.sm2" returns 0x1380.
     *
     * @return the parsed offset, or -1 if none found
     */
    static int parseFilenameOffset(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0) return -1;
        int prevDot = filename.lastIndexOf('.', lastDot - 1);
        if (prevDot < 0) return -1;
        String hex = filename.substring(prevDot + 1, lastDot);
        if (hex.length() != 4) return -1;
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Auto-detect SeqBase by analyzing header pointers.
     * Ports SMPSPlay's GuessSMPSOffset algorithm:
     * finds the smallest track pointer and subtracts the header end offset.
     */
    static int guessSeqBase(byte[] data, int fmCount, int psgCount,
                            int voicePtr, int[] fmPointers, int[] psgPointers) {
        int headerEnd = 6 + fmCount * 4 + psgCount * 6;

        // Find the smallest track pointer
        int minTrackPtr = Integer.MAX_VALUE;
        for (int i = 0; i < fmCount; i++) {
            if (fmPointers[i] > 0 && fmPointers[i] < minTrackPtr) {
                minTrackPtr = fmPointers[i];
            }
        }
        for (int i = 0; i < psgCount; i++) {
            if (psgPointers[i] > 0 && psgPointers[i] < minTrackPtr) {
                minTrackPtr = psgPointers[i];
            }
        }

        if (minTrackPtr == Integer.MAX_VALUE) {
            return 0; // no valid track pointers
        }

        // If voice pointer is smaller but within 0x180, use it instead
        if (voicePtr > 0 && voicePtr < minTrackPtr && (minTrackPtr - voicePtr) <= 0x180) {
            minTrackPtr = voicePtr;
        }

        return Math.max(0, minTrackPtr - headerEnd);
    }

    /**
     * Strip a trailing F6 (JUMP) terminator from track data, replacing with F2 (STOP).
     * PatternCompiler adds its own F6/F2 terminators, so stale jump pointers must be removed.
     */
    private byte[] stripJumpTerminator(byte[] trackData) {
        if (trackData.length >= 3
                && (trackData[trackData.length - 3] & 0xFF) == SmpsCoordFlags.JUMP) {
            byte[] result = new byte[trackData.length - 2];
            System.arraycopy(trackData, 0, result, 0, trackData.length - 3);
            result[result.length - 1] = (byte) SmpsCoordFlags.STOP;
            return result;
        }
        return trackData;
    }

    /**
     * Extracts a track by following reachable control flow from its start pointer.
     *
     * <p>This keeps call/jump/loop targets that may live after the first linear
     * terminator, which is common in SMPS track layouts.
     */
    private byte[] extractReachableTrackData(byte[] data, int start, int seqBase) {
        if (start < 0 || start >= data.length) return new byte[0];

        boolean[] reachable = new boolean[data.length];
        boolean[] queued = new boolean[data.length];
        boolean[] processedEntry = new boolean[data.length];
        Deque<Integer> work = new ArrayDeque<>();
        work.add(start);
        queued[start] = true;

        int maxReach = start;
        while (!work.isEmpty()) {
            int pos = work.removeFirst();
            if (pos < 0 || pos >= data.length) continue;
            if (processedEntry[pos]) continue;
            processedEntry[pos] = true;

            Set<Integer> seenOnPath = new HashSet<>();
            while (pos >= 0 && pos < data.length) {
                if (!seenOnPath.add(pos)) break; // zero-time control-flow cycle

                int cmd = data[pos] & 0xFF;
                int span = commandSpan(data, pos, cmd);
                for (int i = 0; i < span && pos + i < data.length; i++) {
                    reachable[pos + i] = true;
                }
                maxReach = Math.max(maxReach, Math.min(data.length - 1, pos + span - 1));

                if (cmd == SmpsCoordFlags.STOP || cmd == 0x00) {
                    break;
                }

                if (cmd >= 0xE0) {
                    if (cmd == SmpsCoordFlags.JUMP) {
                        int target = resolveInlinePointer(data, pos + 1, seqBase);
                        if (target >= 0 && !queued[target] && !processedEntry[target]) {
                            queued[target] = true;
                            work.add(target);
                        }
                        break;
                    }
                    if (cmd == SmpsCoordFlags.LOOP) {
                        int target = resolveInlinePointer(data, pos + 3, seqBase);
                        if (target >= 0 && !queued[target] && !processedEntry[target]) {
                            queued[target] = true;
                            work.add(target);
                        }
                        pos += span; // fallthrough path is also reachable
                        continue;
                    }
                    if (cmd == SmpsCoordFlags.CALL) {
                        int target = resolveInlinePointer(data, pos + 1, seqBase);
                        if (target >= 0 && !queued[target] && !processedEntry[target]) {
                            queued[target] = true;
                            work.add(target);
                        }
                        pos += span; // continue at caller fallthrough
                        continue;
                    }
                    if (cmd == SmpsCoordFlags.RETURN) {
                        break;
                    }
                    pos += span;
                    continue;
                }

                pos += span;
            }
        }

        if (maxReach < start) return new byte[0];
        return Arrays.copyOfRange(data, start, maxReach + 1);
    }

    private int commandSpan(byte[] data, int pos, int cmd) {
        if (cmd >= 0x80 && cmd <= 0xDF) {
            int span = 1;
            if (pos + 1 < data.length) {
                int next = data[pos + 1] & 0xFF;
                if (next >= 0x01 && next <= 0x7F) span++;
            }
            return span;
        }
        if (cmd >= 0xE0) {
            int paramCount = SmpsCoordFlags.getParamCount(cmd);
            int span = 1 + paramCount;
            return Math.max(1, Math.min(span, data.length - pos));
        }
        return 1;
    }

    private int resolveInlinePointer(byte[] data, int ptrOffset, int seqBase) {
        if (ptrOffset < 0 || ptrOffset + 1 >= data.length) return -1;
        int raw = readLE16(data, ptrOffset);

        if (raw >= 0 && raw < data.length) return raw; // file-relative pointer
        int adjusted = raw - seqBase; // Z80-absolute pointer
        if (adjusted >= 0 && adjusted < data.length) return adjusted;
        return -1;
    }

    /**
     * Convert absolute in-track pointers (F6/F7/F8) to offsets relative to the
     * start of this extracted track, preserving pointers that clearly target
     * outside the extracted byte range.
     */
    private byte[] normalizeTrackPointers(byte[] trackData, int trackStartOffset, int seqBase) {
        if (trackData == null || trackData.length == 0) {
            return trackData;
        }

        byte[] out = trackData.clone();
        int pos = 0;
        while (pos < out.length) {
            int cmd = out[pos] & 0xFF;
            if (cmd >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(cmd);
                if ((cmd == SmpsCoordFlags.JUMP || cmd == SmpsCoordFlags.CALL) && pos + 2 < out.length) {
                    int raw = (out[pos + 1] & 0xFF) | ((out[pos + 2] & 0xFF) << 8);
                    int normalized = normalizePointer(raw, trackStartOffset, out.length, seqBase);
                    out[pos + 1] = (byte) (normalized & 0xFF);
                    out[pos + 2] = (byte) ((normalized >> 8) & 0xFF);
                } else if (cmd == SmpsCoordFlags.LOOP && pos + 4 < out.length) {
                    int raw = (out[pos + 3] & 0xFF) | ((out[pos + 4] & 0xFF) << 8);
                    int normalized = normalizePointer(raw, trackStartOffset, out.length, seqBase);
                    out[pos + 3] = (byte) (normalized & 0xFF);
                    out[pos + 4] = (byte) ((normalized >> 8) & 0xFF);
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
        return out;
    }

    private int normalizePointer(int rawPointer, int trackStartOffset, int trackLength, int seqBase) {
        int local = rawPointer - trackStartOffset;
        if (local >= 0 && local < trackLength) {
            return local;
        }
        int adjusted = rawPointer - seqBase;
        local = adjusted - trackStartOffset;
        if (local >= 0 && local < trackLength) {
            return local;
        }
        return rawPointer;
    }

    /**
     * Scan track bytecode for the F3 (PSG_NOISE) coordination flag.
     * Walks the bytecode properly to avoid matching parameter bytes.
     */
    static boolean containsPsgNoiseFlag(byte[] trackData) {
        int pos = 0;
        while (pos < trackData.length) {
            int cmd = trackData[pos] & 0xFF;
            if (cmd == SmpsCoordFlags.PSG_NOISE) {
                return true;
            }
            if (cmd >= 0xE0 && cmd <= 0xFF) {
                pos++; // skip flag byte
                pos += SmpsCoordFlags.getParamCount(cmd);
                continue;
            }
            pos++;
        }
        return false;
    }

    /**
     * Shift note bytes (0x81-0xDF) by a signed compensation value.
     * Properly skips coordination flag parameters to avoid modifying non-note bytes.
     */
    private byte[] applyNoteCompensation(byte[] data, int compensation) {
        byte[] result = data.clone();
        int pos = 0;
        while (pos < result.length) {
            int b = result[pos] & 0xFF;
            if (b >= 0xE0) {
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else if (b >= 0x81 && b <= 0xDF) {
                int adjusted = Math.max(0x81, Math.min(0xDF, b + compensation));
                result[pos] = (byte) adjusted;
                pos++;
            } else {
                pos++;
            }
        }
        return result;
    }

    /**
     * Decompress DPCM-encoded DAC sample data.
     * Each input byte produces two output samples via high/low nibble delta accumulation.
     */
    static byte[] decompressDpcm(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int accumulator = 0x80;
        for (int i = 0; i < compressed.length; i++) {
            int b = compressed[i] & 0xFF;
            accumulator = (accumulator + DPCM_DELTA_TABLE[(b >> 4) & 0x0F]) & 0xFF;
            output[i * 2] = (byte) accumulator;
            accumulator = (accumulator + DPCM_DELTA_TABLE[b & 0x0F]) & 0xFF;
            output[i * 2 + 1] = (byte) accumulator;
        }
        return output;
    }

    /**
     * Parse a PSG.lst binary file into PsgEnvelope entries.
     * Format: "LST_ENV" (7 bytes) + count (1 byte) + per-envelope: nameLen + name + dataLen + data.
     */
    static List<PsgEnvelope> parsePsgLst(byte[] data) {
        List<PsgEnvelope> envelopes = new ArrayList<>();
        if (data.length < 8) return envelopes;

        String header = new String(data, 0, 7, StandardCharsets.US_ASCII);
        if (!"LST_ENV".equals(header)) return envelopes;

        int count = data[7] & 0xFF;
        int pos = 8;
        for (int i = 0; i < count && pos < data.length; i++) {
            int nameLen = data[pos++] & 0xFF;
            if (pos + nameLen > data.length) break;
            String envName = new String(data, pos, nameLen, StandardCharsets.US_ASCII);
            pos += nameLen;
            if (pos >= data.length) break;
            int dataLen = data[pos++] & 0xFF;
            if (pos + dataLen > data.length) break;
            byte[] envData = new byte[dataLen];
            System.arraycopy(data, pos, envData, 0, dataLen);
            pos += dataLen;
            envelopes.add(new PsgEnvelope(envName, envData));
        }
        return envelopes;
    }

    /**
     * Load companion DAC samples from the parent directory.
     * Parses DAC.ini (INI with [ID] sections for Compr/File/Rate per sample)
     * and DefDrum.txt ([Drums] section mapping noteHex to dacId+rate).
     */
    private void loadDacSamples(File parentDir, Song song) {
        File dacIni = new File(parentDir, "DAC.ini");
        if (!dacIni.exists()) return;

        try {
            // Parse DAC.ini sections: dacId -> {compr, file, rate}
            Map<Integer, DacIniEntry> dacSections = parseDacIni(dacIni);
            if (dacSections.isEmpty()) return;

            // Parse DefDrum.txt: drumIndex -> {dacId, rate}
            TreeMap<Integer, int[]> drumMap = parseDefDrumTxt(parentDir);

            if (!drumMap.isEmpty()) {
                int maxIndex = drumMap.lastKey();
                for (int i = 0; i <= maxIndex; i++) {
                    int[] drum = drumMap.get(i);
                    if (drum != null) {
                        DacIniEntry section = dacSections.get(drum[0]);
                        if (section != null) {
                            byte[] rawData = loadDacFileWithFallback(
                                    parentDir, section.file, section.dpcm);
                            if (rawData != null) {
                                String sampleName = new File(section.file).getName()
                                        .replaceAll("\\.[^.]+$", "");
                                song.getDacSamples().add(
                                        new DacSample(sampleName, rawData, drum[1]));
                                continue;
                            }
                        }
                    }
                    song.getDacSamples().add(
                            new DacSample("Empty", new byte[]{(byte) 0x80}, 0));
                }
            } else {
                // Fall back to DAC.ini sections in numeric order
                for (var entry : new TreeMap<>(dacSections).entrySet()) {
                    DacIniEntry sec = entry.getValue();
                    byte[] rawData = loadDacFileWithFallback(parentDir, sec.file, sec.dpcm);
                    if (rawData == null) continue;
                    String sampleName = new File(sec.file).getName()
                            .replaceAll("\\.[^.]+$", "");
                    song.getDacSamples().add(new DacSample(sampleName, rawData, sec.rate));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Companion DAC files are optional
        }
    }

    /** Parsed DAC.ini section entry. */
    private record DacIniEntry(boolean dpcm, String file, int rate) {}

    /**
     * Parse DAC.ini into per-sample sections.
     * Format: INI with [ID] sections (hex, no prefix), each with Compr/File/Rate fields.
     */
    private Map<Integer, DacIniEntry> parseDacIni(File dacIni) throws IOException {
        Map<Integer, DacIniEntry> sections = new LinkedHashMap<>();
        int currentId = -1;
        boolean currentDpcm = false;
        String currentFile = null;
        int currentRate = 0;

        for (String line : Files.readAllLines(dacIni.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;

            // Section header: [81], [82], etc.
            if (line.startsWith("[") && line.endsWith("]")) {
                // Save previous section if complete
                if (currentId >= 0 && currentFile != null) {
                    sections.put(currentId, new DacIniEntry(currentDpcm, currentFile, currentRate));
                }
                String idStr = line.substring(1, line.length() - 1).trim();
                try {
                    currentId = Integer.parseInt(idStr, 16);
                } catch (NumberFormatException e) {
                    currentId = -1; // Non-numeric section like [Main]
                }
                currentDpcm = false;
                currentFile = null;
                currentRate = 0;
                continue;
            }

            // Key = Value within a section
            int eq = line.indexOf('=');
            if (eq < 0 || currentId < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            switch (key) {
                case "Compr" -> currentDpcm = "DPCM".equalsIgnoreCase(val);
                case "File" -> currentFile = val;
                case "Rate" -> currentRate = parseHexOrDec(val);
            }
        }
        // Save last section
        if (currentId >= 0 && currentFile != null) {
            sections.put(currentId, new DacIniEntry(currentDpcm, currentFile, currentRate));
        }
        return sections;
    }

    /**
     * Parse DefDrum.txt for note-to-DAC mapping.
     * Format: [Drums] section with lines: noteHex TAB type TAB dacId TAB rate (all plain hex).
     * Returns map of 0-based drum index to {dacId, rate}.
     */
    private TreeMap<Integer, int[]> parseDefDrumTxt(File parentDir) throws IOException {
        TreeMap<Integer, int[]> drumMap = new TreeMap<>();
        File defDrum = new File(parentDir, "DefDrum.txt");
        if (!defDrum.exists()) return drumMap;

        boolean inDrums = false;
        for (String line : Files.readAllLines(defDrum.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;

            if (line.equalsIgnoreCase("[Drums]")) {
                inDrums = true;
                continue;
            }
            if (line.startsWith("[")) {
                inDrums = false;
                continue;
            }
            if (!inDrums) continue;

            // Drum entry: noteHex TAB type TAB dacId TAB rate (all plain hex)
            String[] parts = line.split("\\s+");
            if (parts.length >= 4 && "DAC".equalsIgnoreCase(parts[1])) {
                try {
                    int noteVal = Integer.parseInt(parts[0], 16);
                    int dacId = Integer.parseInt(parts[2], 16);
                    int rate = Integer.parseInt(parts[3], 16);
                    int index = noteVal - 0x81;
                    if (index >= 0) {
                        drumMap.put(index, new int[]{dacId, rate});
                    }
                } catch (NumberFormatException e) {
                    // Skip malformed lines
                }
            }
        }
        return drumMap;
    }

    /**
     * Load a DAC file, checking uncompressed fallback first, then standard locations.
     * If found in uncompressed directory, returns raw data without decompression.
     */
    private byte[] loadDacFileWithFallback(File parentDir, String filename, boolean dpcm)
            throws IOException {
        String baseName = new File(filename).getName();

        // Try DAC/uncompressed/ first (already decompressed)
        File f = new File(new File(new File(parentDir, "DAC"), "uncompressed"), baseName);
        if (f.exists()) return Files.readAllBytes(f.toPath());

        // Try direct path (handles DAC\DAC_81.bin style paths from DAC.ini)
        f = new File(parentDir, filename.replace('\\', '/'));
        if (f.exists()) {
            byte[] raw = Files.readAllBytes(f.toPath());
            return dpcm ? decompressDpcm(raw) : raw;
        }

        // Try DAC/ subdirectory
        f = new File(new File(parentDir, "DAC"), baseName);
        if (f.exists()) {
            byte[] raw = Files.readAllBytes(f.toPath());
            return dpcm ? decompressDpcm(raw) : raw;
        }

        return null;
    }

    /**
     * Load companion PSG envelopes from PSG.lst in the parent directory.
     */
    private void loadPsgEnvelopes(File parentDir, Song song) {
        File psgLst = new File(parentDir, "PSG.lst");
        if (!psgLst.exists()) return;
        try {
            byte[] data = Files.readAllBytes(psgLst.toPath());
            song.getPsgEnvelopes().addAll(parsePsgLst(data));
        } catch (IOException e) {
            // Companion PSG file is optional
        }
    }

    private static int parseHexOrDec(String value) {
        value = value.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return Integer.parseInt(value.substring(2), 16);
        }
        return Integer.parseInt(value);
    }

    private int readLE16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private byte[] prependFmHeaderState(byte[] trackData, int keyOffset, int volumeOffset) {
        int prefixLen = 0;
        if (keyOffset != 0) prefixLen += 2;
        if (volumeOffset != 0) prefixLen += 2;
        if (prefixLen == 0) return trackData;

        byte[] out = new byte[prefixLen + trackData.length];
        int pos = 0;
        if (keyOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.KEY_DISP;
            out[pos++] = (byte) keyOffset;
        }
        if (volumeOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.VOLUME;
            out[pos++] = (byte) volumeOffset;
        }
        System.arraycopy(trackData, 0, out, pos, trackData.length);
        return out;
    }

    /**
     * Prepend PSG header initialization commands (instrument, key offset, volume)
     * to a decompiled phrase's bytecode. The SMPS header stores these per-channel
     * but they aren't part of the track bytecode, so without this the values are
     * lost in the decompile→recompile round-trip.
     *
     * <p>Like {@link #prependPsgHeaderState}, keeps any leading F3 (PSG_NOISE) first.
     */
    private byte[] prependPsgInitToPhrase(byte[] phraseData, int keyOffset, int volumeOffset,
                                          int instrument) {
        // If the phrase starts with F3 (PSG_NOISE), keep it first — setPsgVolume (EC)
        // writes to hw ch 2 unless noise mode is already enabled.
        boolean hoistNoise = phraseData.length >= 2
                && (phraseData[0] & 0xFF) == SmpsCoordFlags.PSG_NOISE;
        int noiseBytes = hoistNoise ? 2 : 0;

        int prefixLen = noiseBytes;
        if (keyOffset != 0) prefixLen += 2;
        if (volumeOffset != 0) prefixLen += 2;
        if (instrument != 0) prefixLen += 2;
        if (prefixLen == 0) return phraseData;

        byte[] out = new byte[prefixLen + phraseData.length - noiseBytes];
        int pos = 0;
        if (hoistNoise) {
            out[pos++] = phraseData[0]; // F3
            out[pos++] = phraseData[1]; // noise param
        }
        if (keyOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.KEY_DISP;
            out[pos++] = (byte) keyOffset;
        }
        if (volumeOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.PSG_VOLUME;
            out[pos++] = (byte) volumeOffset;
        }
        if (instrument != 0) {
            out[pos++] = (byte) SmpsCoordFlags.PSG_INSTRUMENT;
            out[pos++] = (byte) instrument;
        }
        System.arraycopy(phraseData, noiseBytes, out, pos, phraseData.length - noiseBytes);
        return out;
    }

    private byte[] prependPsgHeaderState(byte[] trackData, int keyOffset, int volumeOffset,
                                         int instrument, int modEnv) {
        // If the track starts with F3 (PSG_NOISE), it MUST be the very first flag
        // processed by the sequencer.  setPsgVolume (EC) calls refreshVolume which
        // writes to hw ch 2 when noiseMode is false.  If EC runs before F3 enables
        // noise mode, hw ch 2 gets a non-silent volume that never gets cleared
        // (subsequent writes redirect to hw ch 3).
        boolean hoistNoise = trackData.length >= 2
                && (trackData[0] & 0xFF) == SmpsCoordFlags.PSG_NOISE;
        int noiseBytes = hoistNoise ? 2 : 0;

        int prefixLen = noiseBytes;
        if (keyOffset != 0) prefixLen += 2;
        if (volumeOffset != 0) prefixLen += 2;
        if (instrument != 0) prefixLen += 2;
        // Per-track PSG modulation envelope id exists in header but has no direct
        // bytecode equivalent in this model, so it is not preserved here.
        if (prefixLen == 0) return trackData;

        byte[] out = new byte[prefixLen + trackData.length - noiseBytes];
        int pos = 0;
        if (hoistNoise) {
            out[pos++] = trackData[0]; // F3
            out[pos++] = trackData[1]; // noise param
        }
        if (keyOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.KEY_DISP;
            out[pos++] = (byte) keyOffset;
        }
        if (volumeOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.PSG_VOLUME;
            out[pos++] = (byte) volumeOffset;
        }
        if (instrument != 0) {
            out[pos++] = (byte) SmpsCoordFlags.PSG_INSTRUMENT;
            out[pos++] = (byte) instrument;
        }
        System.arraycopy(trackData, noiseBytes, out, pos, trackData.length - noiseBytes);
        return out;
    }
}
