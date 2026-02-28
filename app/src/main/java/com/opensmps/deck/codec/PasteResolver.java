package com.opensmps.deck.codec;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves instrument references when pasting track data between different songs.
 * Scans pasted bytecode for voice/PSG references, auto-remaps matching instruments,
 * and rewrites indices in the pasted data.
 *
 * <p>This class handles the non-UI portion of cross-song paste resolution.
 * UI interaction (e.g., showing a dialog for unresolved instruments) is handled
 * by the caller.
 */
public final class PasteResolver {
    private PasteResolver() {}

    /**
     * Result of scanning channel data for instrument references and attempting auto-remap.
     *
     * @param voiceMap        auto-resolved voice index mappings (src to dst)
     * @param psgMap          auto-resolved PSG index mappings (src to dst)
     * @param unresolvedVoices voice indices that could not be auto-resolved
     * @param unresolvedPsg    PSG indices that could not be auto-resolved
     * @param allVoices        all voice indices found in the channel data
     * @param allPsg           all PSG indices found in the channel data
     */
    public record ScanResult(
            Map<Integer, Integer> voiceMap,
            Map<Integer, Integer> psgMap,
            Set<Integer> unresolvedVoices,
            Set<Integer> unresolvedPsg,
            Set<Integer> allVoices,
            Set<Integer> allPsg
    ) {}

    /**
     * Scans channel data for instrument references and attempts auto-remapping
     * against the destination song's voice bank and PSG envelopes.
     *
     * @param channelData     pasted channel data arrays
     * @param srcVoices       source song's FM voice bank
     * @param srcPsgEnvelopes source song's PSG envelopes
     * @param dstSong         destination song
     * @return scan result with auto-resolved mappings and unresolved indices
     */
    public static ScanResult scanAndAutoRemap(byte[][] channelData, List<FmVoice> srcVoices,
                                               List<PsgEnvelope> srcPsgEnvelopes, Song dstSong) {
        Set<Integer> allVoices = new LinkedHashSet<>();
        Set<Integer> allPsg = new LinkedHashSet<>();
        for (byte[] data : channelData) {
            InstrumentRemapper.ScanResult scan = InstrumentRemapper.scan(data);
            allVoices.addAll(scan.voiceIndices());
            allPsg.addAll(scan.psgIndices());
        }

        Map<Integer, Integer> voiceMap = InstrumentRemapper.autoRemap(
                srcVoices, dstSong.getVoiceBank(), allVoices);
        Map<Integer, Integer> psgMap = InstrumentRemapper.autoRemapPsg(
                srcPsgEnvelopes, dstSong.getPsgEnvelopes(), allPsg);

        Set<Integer> unresolvedVoices = new LinkedHashSet<>(allVoices);
        unresolvedVoices.removeAll(voiceMap.keySet());
        Set<Integer> unresolvedPsg = new LinkedHashSet<>(allPsg);
        unresolvedPsg.removeAll(psgMap.keySet());

        return new ScanResult(voiceMap, psgMap, unresolvedVoices, unresolvedPsg, allVoices, allPsg);
    }

    /**
     * Returns true if the scan result has no instrument references at all
     * (no rewriting needed).
     */
    public static boolean hasNoInstruments(ScanResult result) {
        return result.allVoices().isEmpty() && result.allPsg().isEmpty();
    }

    /**
     * Returns true if all instrument references were auto-resolved
     * (no user interaction needed).
     */
    public static boolean isFullyResolved(ScanResult result) {
        return result.unresolvedVoices().isEmpty() && result.unresolvedPsg().isEmpty();
    }

    /**
     * Rewrites instrument indices in all channel data arrays according to the given maps.
     *
     * @param channelData pasted channel data arrays
     * @param voiceMap    voice index remapping (src to dst)
     * @param psgMap      PSG index remapping (src to dst)
     * @return new channel data arrays with rewritten indices
     */
    public static byte[][] rewriteAll(byte[][] channelData, Map<Integer, Integer> voiceMap,
                                       Map<Integer, Integer> psgMap) {
        byte[][] result = new byte[channelData.length][];
        for (int i = 0; i < channelData.length; i++) {
            result[i] = InstrumentRemapper.rewrite(channelData[i], voiceMap, psgMap);
        }
        return result;
    }
}
