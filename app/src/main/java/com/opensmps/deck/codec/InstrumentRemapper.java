package com.opensmps.deck.codec;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.smps.SmpsCoordFlags;

import java.util.*;

/**
 * Scans SMPS bytecode for instrument references (SET_VOICE, PSG_INSTRUMENT)
 * and rewrites indices for cross-song paste operations.
 *
 * <p>When pasting track data from one song into another, instrument indices
 * may refer to different voices/envelopes. This utility detects which
 * instruments are referenced, matches them by byte-identical data, and
 * rewrites the indices so the pasted data sounds correct in the target song.
 */
public final class InstrumentRemapper {

    private InstrumentRemapper() {}

    /**
     * Result of scanning bytecode for instrument references.
     *
     * @param voiceIndices set of FM voice indices found (from SET_VOICE commands)
     * @param psgIndices   set of PSG envelope indices found (from PSG_INSTRUMENT commands)
     */
    public record ScanResult(Set<Integer> voiceIndices, Set<Integer> psgIndices) {
        public ScanResult {
            voiceIndices = Collections.unmodifiableSet(new LinkedHashSet<>(voiceIndices));
            psgIndices = Collections.unmodifiableSet(new LinkedHashSet<>(psgIndices));
        }
    }

    /**
     * Scan SMPS bytecode and collect all referenced instrument indices.
     *
     * <p>Walks the bytecode, using {@link SmpsCoordFlags#getParamCount(int)} to
     * correctly skip over coordination flag parameters. For each SET_VOICE (0xEF)
     * command, the parameter byte is recorded as a voice index. For each
     * PSG_INSTRUMENT (0xF5) command, the parameter byte is recorded as a PSG index.
     *
     * @param data raw SMPS bytecode
     * @return scan result containing the sets of referenced voice and PSG indices
     */
    public static ScanResult scan(byte[] data) {
        Set<Integer> voiceIndices = new LinkedHashSet<>();
        Set<Integer> psgIndices = new LinkedHashSet<>();

        if (data == null || data.length == 0) {
            return new ScanResult(voiceIndices, psgIndices);
        }

        int pos = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;

            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);

                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < data.length) {
                    voiceIndices.add(data[pos + 1] & 0xFF);
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < data.length) {
                    psgIndices.add(data[pos + 1] & 0xFF);
                }

                pos += 1 + paramCount;
            } else {
                // Note, rest, duration, or other non-flag byte
                pos++;
            }
        }

        return new ScanResult(voiceIndices, psgIndices);
    }

    /**
     * Rewrite instrument indices in SMPS bytecode according to the given maps.
     *
     * <p>Clones the input data and walks the bytecode the same way as {@link #scan}.
     * For SET_VOICE commands, if the param byte's unsigned value is a key in
     * {@code voiceMap}, it is replaced with the mapped value. Likewise for
     * PSG_INSTRUMENT commands with {@code psgMap}.
     *
     * @param data     raw SMPS bytecode
     * @param voiceMap mapping from source voice index to destination voice index
     * @param psgMap   mapping from source PSG index to destination PSG index
     * @return new byte array with rewritten indices
     */
    public static byte[] rewrite(byte[] data, Map<Integer, Integer> voiceMap, Map<Integer, Integer> psgMap) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        byte[] result = data.clone();
        int pos = 0;

        while (pos < result.length) {
            int b = result[pos] & 0xFF;

            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);

                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < result.length) {
                    int srcIdx = result[pos + 1] & 0xFF;
                    Integer dstIdx = voiceMap.get(srcIdx);
                    if (dstIdx != null) {
                        result[pos + 1] = (byte) (dstIdx & 0xFF);
                    }
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < result.length) {
                    int srcIdx = result[pos + 1] & 0xFF;
                    Integer dstIdx = psgMap.get(srcIdx);
                    if (dstIdx != null) {
                        result[pos + 1] = (byte) (dstIdx & 0xFF);
                    }
                }

                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }

        return result;
    }

    /**
     * Automatically build a voice index remap by matching byte-identical FM voice data.
     *
     * <p>For each needed source index, compares the source voice's raw data against
     * every destination voice using {@link Arrays#equals(byte[], byte[])}. If an
     * exact match is found, the mapping srcIdx to dstIdx is recorded.
     *
     * @param src    source song's FM voice bank
     * @param dst    destination song's FM voice bank
     * @param needed set of source voice indices that need remapping
     * @return map from source index to destination index (only contains matches)
     */
    public static Map<Integer, Integer> autoRemap(List<FmVoice> src, List<FmVoice> dst, Set<Integer> needed) {
        Map<Integer, Integer> map = new HashMap<>();

        for (int srcIdx : needed) {
            if (srcIdx < 0 || srcIdx >= src.size()) continue;

            byte[] srcData = src.get(srcIdx).getData();

            for (int dstIdx = 0; dstIdx < dst.size(); dstIdx++) {
                byte[] dstData = dst.get(dstIdx).getData();
                if (Arrays.equals(srcData, dstData)) {
                    map.put(srcIdx, dstIdx);
                    break;
                }
            }
        }

        return map;
    }

    /**
     * Automatically build a PSG envelope index remap by matching byte-identical envelope data.
     *
     * <p>Same logic as {@link #autoRemap} but for PSG envelopes.
     *
     * @param src    source song's PSG envelope list
     * @param dst    destination song's PSG envelope list
     * @param needed set of source PSG indices that need remapping
     * @return map from source index to destination index (only contains matches)
     */
    public static Map<Integer, Integer> autoRemapPsg(List<PsgEnvelope> src, List<PsgEnvelope> dst, Set<Integer> needed) {
        Map<Integer, Integer> map = new HashMap<>();

        for (int srcIdx : needed) {
            if (srcIdx < 0 || srcIdx >= src.size()) continue;

            byte[] srcData = src.get(srcIdx).getData();

            for (int dstIdx = 0; dstIdx < dst.size(); dstIdx++) {
                byte[] dstData = dst.get(dstIdx).getData();
                if (Arrays.equals(srcData, dstData)) {
                    map.put(srcIdx, dstIdx);
                    break;
                }
            }
        }

        return map;
    }
}
