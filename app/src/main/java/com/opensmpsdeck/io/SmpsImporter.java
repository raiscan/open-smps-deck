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
    private static final int DAC_MODEL_CHANNEL = 5;

    /**
     * Shared FM instrument library (SMPSPlay's GlobalInsLib, e.g. InsSet.17D8.bin).
     * Used when a song's voice pointer resolves outside the song file.
     *
     * @param data raw library bytes (consecutive 25-byte voices)
     * @param baseAddress Z80 address of the library start (encoded in the filename)
     */
    record GlobalInsLib(byte[] data, int baseAddress) {}

    /**
     * Whether the current import uses SMPS 68k pointer conventions (Sonic 1):
     * big-endian header pointers and PC-relative in-stream jump pointers.
     */
    private boolean use68kPointers;

    /** Coordination flag dialect of the current import (sizes/meanings per driver). */
    private SmpsCoordFlags.Dialect dialect = SmpsCoordFlags.Dialect.S2;

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

        GlobalInsLib insLib = findGlobalInsLib(file.getParentFile());
        Song song = importData(data, name, seqBase, mode, insLib);
        song.setSmpsMode(mode);

        // Load companion files from parent directory
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            loadDacSamples(parentDir, song);
            loadPsgEnvelopes(parentDir, song);
            loadModEnvelopes(parentDir, song);
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
        return importData(data, name, seqBase, mode, null);
    }

    /**
     * Import raw SMPS binary data with an explicit SeqBase and optional shared
     * instrument library for songs whose voice table lives outside the file.
     */
    Song importData(byte[] data, String name, int seqBase, SmpsMode mode, GlobalInsLib insLib) {
        if (data.length < 6) {
            throw new IllegalArgumentException("SMPS data too short: " + data.length + " bytes");
        }
        use68kPointers = (mode == SmpsMode.S1);
        dialect = mode.dialect();

        Song song = new Song();
        song.getPatterns().clear();
        song.getOrderList().clear();
        song.setName(name != null ? name.replaceAll("\\.[^.]+$", "") : "Imported");

        // Parse header (raw pointers before SeqBase adjustment)
        int voicePtr = readPtr16(data, 0);
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
            fmPointers[i] = readPtr16(data, offset);
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
            psgPointers[i] = readPtr16(data, offset);
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

        // Extract voices from the in-file voice table, or fall back to the shared
        // instrument library when the pointer resolves outside the file
        // (mirrors SMPSPlay loader_smps.c instrument table resolution).
        if (voicePtr > 0 && voicePtr + FmVoice.VOICE_SIZE <= data.length) {
            int voiceCount = estimateVoiceCount(data, voicePtr, fmPointers, psgPointers);
            for (int i = 0; i < voiceCount; i++) {
                int vOffset = voicePtr + i * FmVoice.VOICE_SIZE;
                if (vOffset + FmVoice.VOICE_SIZE > data.length) break;
                byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
                System.arraycopy(data, vOffset, voiceData, 0, FmVoice.VOICE_SIZE);
                voiceData = FmVoiceLayoutNormalizer.normalizeForMode(voiceData, mode);
                song.getVoiceBank().add(new FmVoice("Voice " + i, voiceData));
            }
        } else if (insLib != null && insLib.data().length >= FmVoice.VOICE_SIZE) {
            // Raw (pre-SeqBase) pointer mid-library starts the table there;
            // anything else (at the base, or out of range) uses the full library
            int rawVoicePtr = voicePtr + Math.max(0, seqBase);
            int start = 0;
            if (rawVoicePtr > insLib.baseAddress()
                    && rawVoicePtr < insLib.baseAddress() + insLib.data().length) {
                start = rawVoicePtr - insLib.baseAddress();
            }
            int voiceCount = Math.min((insLib.data().length - start) / FmVoice.VOICE_SIZE, 64);
            for (int i = 0; i < voiceCount; i++) {
                byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
                System.arraycopy(insLib.data(), start + i * FmVoice.VOICE_SIZE,
                        voiceData, 0, FmVoice.VOICE_SIZE);
                voiceData = FmVoiceLayoutNormalizer.normalizeForMode(voiceData, mode);
                song.getVoiceBank().add(new FmVoice("GblIns " + i, voiceData));
            }
        }

        // Create a single pattern with all track data
        Pattern pattern = new Pattern(0, 64);
        // Parallel array: normalized data with JUMP intact for hierarchical decompilation
        byte[][] decompileData = new byte[Pattern.CHANNEL_COUNT][];
        // Per-channel header values for hierarchical chain initialization
        int[] psgHeaderInstrument = new int[Pattern.CHANNEL_COUNT];
        int[] psgHeaderKey = new int[Pattern.CHANNEL_COUNT];
        int[] psgHeaderVol = new int[Pattern.CHANNEL_COUNT];
        int[] fmHeaderKey = new int[Pattern.CHANNEL_COUNT];
        int[] fmHeaderVol = new int[Pattern.CHANNEL_COUNT];
        int[] psgHeaderMod = new int[Pattern.CHANNEL_COUNT];

        // Extract FM track data.
        // The sequencer's fmChannelOrder maps entry 0→DAC, 1→FM1, ... 6→FM6.
        // Map to model channels: entry 0 → model ch 5 (DAC), entries 1-5 →
        // model ch i-1 (FM1-FM5). A 7th entry is FM6 taking the hardware
        // channel DAC would use; it replaces the (stub) DAC on model ch 5.
        for (int i = 0; i < fmCount && i <= FM_CHANNEL_COUNT; i++) {
            int modelCh = (i == 0 || i == FM_CHANNEL_COUNT) ? DAC_MODEL_CHANNEL : i - 1;
            if (i == FM_CHANNEL_COUNT) {
                song.setDacChannelFm6(true);
            }
            int ptr = fmPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                TrackExtract extract = extractTrack(data, ptr, seqBase);
                if (extract.data().length > 0) {
                    decompileData[modelCh] = extract.data();
                    fmHeaderKey[modelCh] = fmKeys[i];
                    fmHeaderVol[modelCh] = fmVols[i];
                    byte[] trackData = stripJumpTerminator(extract);
                    trackData = prependFmHeaderState(trackData, fmKeys[i], fmVols[i]);
                    pattern.setTrackData(modelCh, trackData);
                }
            }
        }

        // Extract PSG track data
        for (int i = 0; i < psgCount && i < PSG_CHANNEL_COUNT; i++) {
            int ptr = psgPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                TrackExtract extract = extractTrack(data, ptr, seqBase);
                if (extract.data().length > 0) {
                    // Detect PSG noise mode. Only the last PSG track (the
                    // tone3/noise hardware slot) can drive the noise
                    // generator; F3 flags on earlier PSG tracks are
                    // configuration writes, not channel moves.
                    int channelIndex = FM_CHANNEL_COUNT + i;
                    if (i == psgCount - 1 && containsPsgNoiseFlag(extract.data())) {
                        channelIndex = FM_CHANNEL_COUNT + 3; // PSG Noise = channel 9
                    }
                    decompileData[channelIndex] = extract.data();
                    psgHeaderInstrument[channelIndex] = psgInstruments[i];
                    psgHeaderKey[channelIndex] = psgKeys[i];
                    psgHeaderVol[channelIndex] = psgVols[i];
                    psgHeaderMod[channelIndex] = psgMods[i];
                    byte[] trackData = stripJumpTerminator(extract);
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

            // Reverse note compensation for S1/S3K FM channels only (0-4,
            // plus channel 5 when it carries FM6 instead of DAC).
            // PSG (6-9) uses baseNoteOffset=0 in all modes, no compensation needed.
            boolean fmChannel = ch < 5 || (ch == DAC_MODEL_CHANNEL && song.isDacChannelFm6());
            if (noteCompensation != 0 && fmChannel) {
                trackData = applyNoteCompensation(trackData, noteCompensation);
            }

            ChannelType channelType = (ch == DAC_MODEL_CHANNEL && song.isDacChannelFm6())
                    ? ChannelType.FM
                    : ChannelType.fromChannelIndex(ch);
            HierarchyDecompiler.DecompileResult result =
                    HierarchyDecompiler.decompileTrack(trackData, channelType, dialect);

            // Remap phrase IDs from local decompiler library to global song library
            Map<Integer, Integer> idMap = new HashMap<>();
            for (Phrase localPhrase : result.phrases()) {
                Phrase newPhrase = library.createPhrase(localPhrase.getName(), localPhrase.getChannelType());
                newPhrase.setData(localPhrase.getDataDirect());
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

            // Preserve per-channel header state (key displacement, volume
            // attenuation, PSG instrument). The SMPS header carries these but
            // the compiler writes zeros, so they must live in the chain itself.
            if (ch >= FM_CHANNEL_COUNT) {
                applyHeaderInit(chain, library, channelType, true,
                        psgHeaderKey[ch], psgHeaderVol[ch], psgHeaderInstrument[ch],
                        psgHeaderMod[ch]);
            } else {
                applyHeaderInit(chain, library, channelType, false,
                        fmHeaderKey[ch], fmHeaderVol[ch], 0, 0);
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

            if (SmpsCoordFlags.isTrackEnd(cmd, dialect)) {
                return pos + 1; // track end, include it
            }
            if (cmd == SmpsCoordFlags.JUMP) {
                return pos + 1 + SmpsCoordFlags.getParamCount(cmd, dialect); // F6 + 2-byte pointer
            }

            // Skip coordination flags with parameters
            if (cmd >= 0xE0 && cmd <= 0xFF) {
                int params = flagParamBytes(data, pos, cmd);
                pos++; // skip the flag byte
                pos += params;
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
     * Result of reachability-based track extraction.
     *
     * @param data track bytes: the main stream (starting at the track's entry
     *             point) followed by any reachable bytes that preceded the entry
     *             point in the file (relocated subroutines)
     * @param mainLength length of the main stream within {@code data}
     */
    record TrackExtract(byte[] data, int mainLength) {}

    /**
     * Strip the main stream's trailing F6 (JUMP) terminator, replacing it with
     * F2 (STOP). PatternCompiler adds its own terminators, so stale jump
     * pointers must be removed. When relocated subroutines follow the main
     * stream, the JUMP is overwritten in place (padded with STOP bytes) so
     * pointers into the subroutine block stay valid.
     */
    private byte[] stripJumpTerminator(TrackExtract extract) {
        byte[] trackData = extract.data();
        int mainLen = extract.mainLength();
        if (mainLen >= 3 && mainLen <= trackData.length
                && (trackData[mainLen - 3] & 0xFF) == SmpsCoordFlags.JUMP) {
            if (mainLen == trackData.length) {
                byte[] result = new byte[trackData.length - 2];
                System.arraycopy(trackData, 0, result, 0, trackData.length - 3);
                result[result.length - 1] = (byte) SmpsCoordFlags.STOP;
                return result;
            }
            byte[] result = trackData.clone();
            result[mainLen - 3] = (byte) SmpsCoordFlags.STOP;
            result[mainLen - 2] = (byte) SmpsCoordFlags.STOP;
            result[mainLen - 1] = (byte) SmpsCoordFlags.STOP;
            return result;
        }
        return trackData;
    }

    /**
     * Extracts a track by following reachable control flow from its start pointer
     * and normalizes in-track pointers to track-local little-endian offsets.
     *
     * <p>Keeps call/jump/loop targets that live after the first linear terminator
     * AND targets that precede the track's entry point (rips commonly share
     * subroutines placed before the track start — e.g. Sonic 2's Super Sonic
     * theme). Pre-start regions are relocated after the main stream.
     */
    private TrackExtract extractTrack(byte[] data, int start, int seqBase) {
        if (start < 0 || start >= data.length) return new TrackExtract(new byte[0], 0);

        boolean[] reachable = new boolean[data.length];
        boolean[] queued = new boolean[data.length];
        boolean[] processedEntry = new boolean[data.length];
        Deque<Integer> work = new ArrayDeque<>();
        work.add(start);
        queued[start] = true;

        int maxReach = start;
        int minReach = start;
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
                minReach = Math.min(minReach, pos);

                if (SmpsCoordFlags.isTrackEnd(cmd, dialect) || cmd == 0x00) {
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
                    if (SmpsCoordFlags.isReturn(cmd, dialect)) {
                        break;
                    }
                    pos += span;
                    continue;
                }

                pos += span;
            }
        }

        if (maxReach < start) return new TrackExtract(new byte[0], 0);

        byte[] out;
        int regionBase;  // file offset corresponding to local offset `prelude`
        int prelude;     // bytes before the contiguous region copy
        if (minReach < start) {
            // Reachable code precedes the entry point (shared streams or
            // subroutines). Keep the whole region contiguous — borrowed
            // streams may flow across the entry point — and enter through a
            // 3-byte JUMP prelude to the real start.
            int regionLen = maxReach + 1 - minReach;
            prelude = 3;
            regionBase = minReach;
            out = new byte[prelude + regionLen];
            int entry = prelude + (start - minReach);
            out[0] = (byte) SmpsCoordFlags.JUMP;
            out[1] = (byte) (entry & 0xFF);
            out[2] = (byte) ((entry >> 8) & 0xFF);
            System.arraycopy(data, minReach, out, prelude, regionLen);
        } else {
            prelude = 0;
            regionBase = start;
            out = Arrays.copyOfRange(data, start, maxReach + 1);
        }

        remapTrackPointers(out, regionBase, prelude, seqBase);
        return new TrackExtract(out, out.length);
    }

    /**
     * Normalize every in-track pointer (F6/F7/F8) in the extracted buffer to a
     * track-local little-endian offset. Pointers that resolve outside the
     * extracted region keep their raw bytes.
     *
     * @param regionBase file offset of the byte at local offset {@code prelude}
     * @param prelude    number of synthetic bytes before the copied region
     */
    private void remapTrackPointers(byte[] out, int regionBase, int prelude, int seqBase) {
        int pos = prelude; // synthetic prelude pointer is already local
        while (pos < out.length) {
            int cmd = out[pos] & 0xFF;
            if (cmd >= 0xE0) {
                int paramCount = flagParamBytes(out, pos, cmd);
                if ((cmd == SmpsCoordFlags.JUMP || cmd == SmpsCoordFlags.CALL) && pos + 2 < out.length) {
                    remapPointerWord(out, pos + 1, regionBase, prelude, seqBase);
                } else if (cmd == SmpsCoordFlags.LOOP && pos + 4 < out.length) {
                    remapPointerWord(out, pos + 3, regionBase, prelude, seqBase);
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
    }

    private void remapPointerWord(byte[] out, int ptrPos, int regionBase, int prelude,
                                  int seqBase) {
        int raw = readPtr16(out, ptrPos);
        int local;
        if (use68kPointers) {
            // PC-relative from the pointer word's ORIGINAL file offset + 1
            int filePtrPos = regionBase + (ptrPos - prelude);
            int targetFile = filePtrPos + 1 + (short) raw;
            local = mapFileOffsetToLocal(targetFile, regionBase, prelude, out.length);
        } else {
            // Try file-relative first, then Z80-absolute (matches resolveInlinePointer)
            local = mapFileOffsetToLocal(raw, regionBase, prelude, out.length);
            if (local < 0) {
                local = mapFileOffsetToLocal(raw - seqBase, regionBase, prelude, out.length);
            }
        }
        if (local >= 0) {
            out[ptrPos] = (byte) (local & 0xFF);
            out[ptrPos + 1] = (byte) ((local >> 8) & 0xFF);
        }
    }

    /** Map a file offset into the extracted track's local space, or -1 if outside. */
    private static int mapFileOffsetToLocal(int fileOffset, int regionBase, int prelude,
                                            int outLength) {
        int local = prelude + (fileOffset - regionBase);
        return (local >= prelude && local < outLength) ? local : -1;
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
            int span = 1 + flagParamBytes(data, pos, cmd);
            return Math.max(1, Math.min(span, data.length - pos));
        }
        return 1;
    }

    /**
     * Total parameter bytes for the flag at {@code pos} in the current dialect.
     * For the S3K FF meta prefix this includes the sub-command's own parameters.
     */
    private int flagParamBytes(byte[] data, int pos, int cmd) {
        int params = SmpsCoordFlags.getParamCount(cmd, dialect);
        if (dialect == SmpsCoordFlags.Dialect.S3K
                && cmd == SmpsCoordFlags.S3K_META && pos + 1 < data.length) {
            params += SmpsCoordFlags.getMetaParamCount(data[pos + 1] & 0xFF);
        }
        return params;
    }

    private int resolveInlinePointer(byte[] data, int ptrOffset, int seqBase) {
        if (ptrOffset < 0 || ptrOffset + 1 >= data.length) return -1;
        int raw = readPtr16(data, ptrOffset);

        if (use68kPointers) {
            // SMPS 68k: dc.w loc-*-1 — signed offset from (pointer offset + 1)
            int target = ptrOffset + 1 + (short) raw;
            return (target >= 0 && target < data.length) ? target : -1;
        }

        if (raw >= 0 && raw < data.length) return raw; // file-relative pointer
        int adjusted = raw - seqBase; // Z80-absolute pointer
        if (adjusted >= 0 && adjusted < data.length) return adjusted;
        return -1;
    }


    /**
     * Scan track bytecode for the F3 (PSG_NOISE) coordination flag.
     * Walks the bytecode properly to avoid matching parameter bytes.
     */
    boolean containsPsgNoiseFlag(byte[] trackData) {
        // Walk the PLAYBACK stream (following gotos and calls) rather than
        // scanning the raw buffer: extracted tracks may contain other
        // channels' bytes when streams are shared, and another channel's F3
        // must not flip this channel into noise mode.
        boolean[] visited = new boolean[trackData.length];
        Deque<Integer> work = new ArrayDeque<>();
        work.add(0);
        int guard = 0;
        while (!work.isEmpty() && guard++ < 4096) {
            int pos = work.poll();
            while (pos >= 0 && pos < trackData.length && !visited[pos]) {
                visited[pos] = true;
                int cmd = trackData[pos] & 0xFF;
                if (cmd == SmpsCoordFlags.PSG_NOISE) {
                    return true;
                }
                if (cmd == SmpsCoordFlags.JUMP && pos + 2 < trackData.length) {
                    int t = (trackData[pos + 1] & 0xFF) | ((trackData[pos + 2] & 0xFF) << 8);
                    if (t >= 0 && t < trackData.length && !visited[t]) {
                        pos = t;
                        continue;
                    }
                    break;
                }
                if (cmd == SmpsCoordFlags.CALL && pos + 2 < trackData.length) {
                    int t = (trackData[pos + 1] & 0xFF) | ((trackData[pos + 2] & 0xFF) << 8);
                    if (t >= 0 && t < trackData.length && !visited[t]) {
                        work.add(t);
                    }
                    pos += 3;
                    continue;
                }
                if (SmpsCoordFlags.isTrackEnd(cmd, dialect)
                        || SmpsCoordFlags.isReturn(cmd, dialect)) {
                    break;
                }
                if (cmd >= 0xE0) {
                    pos += 1 + flagParamBytes(trackData, pos, cmd);
                } else {
                    pos++;
                }
            }
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
                pos += 1 + flagParamBytes(result, pos, b);
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
        return DacCodec.decompressDpcm(compressed);
    }

    /**
     * Parse a PSG.lst binary file into PsgEnvelope entries.
     * Format: "LST_ENV" (7 bytes) + count (1 byte) + per-envelope: nameLen + name + dataLen + data.
     */
    static List<PsgEnvelope> parsePsgLst(byte[] data) {
        return EnvelopeListParser.parse(data);
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
            return dpcm ? DacCodec.decompressDpcm(raw) : raw;
        }

        // Try DAC/ subdirectory
        f = new File(new File(parentDir, "DAC"), baseName);
        if (f.exists()) {
            byte[] raw = Files.readAllBytes(f.toPath());
            return dpcm ? DacCodec.decompressDpcm(raw) : raw;
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

    /**
     * Load companion modulation envelopes from Modulat.lst (same LST format as
     * PSG.lst). Bytecode mod envelope IDs are 1-based into this list.
     */
    private void loadModEnvelopes(File parentDir, Song song) {
        File modLst = new File(parentDir, "Modulat.lst");
        if (!modLst.exists()) return;
        try {
            byte[] data = Files.readAllBytes(modLst.toPath());
            song.getModEnvelopes().addAll(parsePsgLst(data));
        } catch (IOException e) {
            // Companion modulation file is optional
        }
    }

    /** Prefix a phrase with an S3K F4 (set modulation envelope) command. */
    private static byte[] prependModEnv(byte[] phraseData, int modEnv) {
        byte[] out = new byte[2 + phraseData.length];
        out[0] = (byte) 0xF4;
        out[1] = (byte) modEnv;
        System.arraycopy(phraseData, 0, out, 2, phraseData.length);
        return out;
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

    /** Read a 16-bit pointer honoring the current import's pointer format. */
    private int readPtr16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        if (use68kPointers) {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /**
     * Locate the shared FM instrument library (SMPSPlay GlobalInsLib) for songs
     * whose voice pointer targets it. Checks the song's directory then its parent
     * (proto rip sets share the parent's library). The library's Z80 base address
     * is encoded in its filename (e.g. InsSet.17D8.bin).
     */
    static GlobalInsLib findGlobalInsLib(File dir) {
        for (int depth = 0; dir != null && depth < 2; dir = dir.getParentFile(), depth++) {
            File libFile = null;

            // Prefer the config.ini GlobalInsLib entry when present
            File configIni = new File(dir, "config.ini");
            if (configIni.exists()) {
                try {
                    for (String line : Files.readAllLines(configIni.toPath(), StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq < 0) continue;
                        if (line.substring(0, eq).trim().equalsIgnoreCase("GlobalInsLib")) {
                            File candidate = new File(dir, line.substring(eq + 1).trim());
                            if (candidate.exists()) libFile = candidate;
                            break;
                        }
                    }
                } catch (IOException e) {
                    // fall through to filename scan
                }
            }

            if (libFile == null) {
                File[] matches = dir.listFiles((d, name) ->
                        name.toLowerCase().matches("insset\\.[0-9a-f]{4}\\.bin"));
                if (matches != null && matches.length > 0) libFile = matches[0];
            }

            if (libFile != null) {
                int base = parseFilenameOffset(libFile.getName());
                if (base < 0) base = 0;
                try {
                    return new GlobalInsLib(Files.readAllBytes(libFile.toPath()), base);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private byte[] prependFmHeaderState(byte[] trackData, int keyOffset, int volumeOffset) {
        int prefixLen = 0;
        if (keyOffset != 0) prefixLen += 2;
        if (volumeOffset != 0) prefixLen += 2;
        if (prefixLen == 0) return trackData;

        byte[] out = new byte[prefixLen + trackData.length];
        int pos = 0;
        if (keyOffset != 0) {
            out[pos++] = (byte) SmpsCoordFlags.transposeAddFlag(dialect);
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
     * Apply the SMPS header's per-channel initialization (key displacement,
     * volume attenuation, PSG instrument) to a decompiled chain.
     *
     * <p>KEY_DISP (E9), VOLUME (E6), and PSG_VOLUME (EC) are ADDITIVE in the
     * sequencer, so the prefix must execute exactly once. When the chain's
     * first phrase provably plays a single time it is baked in directly;
     * otherwise (shared phrase, repeat count, or the chain loops back to entry
     * 0) a dedicated one-shot init phrase is inserted at the chain head and
     * the loop index shifted past it.
     */
    private void applyHeaderInit(Chain chain, PhraseLibrary library, ChannelType type,
                                 boolean psg, int keyOffset, int volumeOffset, int instrument,
                                 int modEnv) {
        // Header modulation envelopes are only expressible in S3K bytecode (F4)
        boolean emitModEnv = psg && modEnv != 0 && dialect == SmpsCoordFlags.Dialect.S3K;
        if (keyOffset == 0 && volumeOffset == 0 && (!psg || instrument == 0) && !emitModEnv) return;
        if (chain.getEntries().isEmpty()) return;

        ChainEntry firstEntry = chain.getEntries().get(0);
        int firstId = firstEntry.getPhraseId();
        long refs = chain.getEntries().stream()
                .filter(e -> e.getPhraseId() == firstId).count();
        boolean loopHitsFirst = chain.getLoopEntryIndex() == 0;
        Phrase firstPhrase = library.getPhrase(firstId);
        if (firstPhrase == null) return;

        if (refs == 1 && firstEntry.getRepeatCount() <= 1 && !loopHitsFirst) {
            byte[] d = firstPhrase.getDataDirect();
            d = psg
                    ? prependPsgInitToPhrase(d, keyOffset, volumeOffset, instrument)
                    : prependFmHeaderState(d, keyOffset, volumeOffset);
            if (emitModEnv) {
                d = prependModEnv(d, modEnv);
            }
            firstPhrase.setData(d);
            return;
        }

        // One-shot init phrase before the body. For PSG noise tracks, carry the
        // F3 noise-mode enable into the init so EC targets the right hw channel
        // (re-running F3 in the body is harmless — it just re-sets the mode).
        byte[] seed = new byte[0];
        byte[] firstData = firstPhrase.getDataDirect();
        if (psg && firstData.length >= 2
                && (firstData[0] & 0xFF) == SmpsCoordFlags.PSG_NOISE) {
            seed = new byte[]{firstData[0], firstData[1]};
        }
        Phrase initPhrase = library.createPhrase("Init", type);
        byte[] initData = psg
                ? prependPsgInitToPhrase(seed, keyOffset, volumeOffset, instrument)
                : prependFmHeaderState(new byte[0], keyOffset, volumeOffset);
        if (emitModEnv) {
            initData = prependModEnv(initData, modEnv);
        }
        initPhrase.setData(initData);
        chain.getEntries().add(0, new ChainEntry(initPhrase.getId()));
        if (chain.getLoopEntryIndex() >= 0) {
            chain.setLoopEntryIndex(chain.getLoopEntryIndex() + 1);
        }
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
            out[pos++] = (byte) SmpsCoordFlags.transposeAddFlag(dialect);
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
            out[pos++] = (byte) SmpsCoordFlags.transposeAddFlag(dialect);
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
