package com.opensmps.deck.io;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Imports raw SMPS binary files into Song models.
 * Works with SMPSPlay .bin rips and exported files.
 *
 * <p>Uses {@link SmpsCoordFlags} for all coordination flag parameter counts
 * to ensure correct bytecode parsing aligned with the Z80 driver.
 */
public class SmpsImporter {

    private static final int FM_CHANNEL_COUNT = 6;
    private static final int PSG_CHANNEL_COUNT = 4;

    /**
     * Import an SMPS binary file as a Song.
     * Uses file-relative pointers (z80StartAddress=0).
     */
    public Song importFile(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        return importData(data, file.getName());
    }

    /**
     * Import raw SMPS binary data as a Song.
     */
    public Song importData(byte[] data, String name) {
        if (data.length < 6) {
            throw new IllegalArgumentException("SMPS data too short: " + data.length + " bytes");
        }

        Song song = new Song();
        song.getPatterns().clear();
        song.getOrderList().clear();
        song.setName(name != null ? name.replaceAll("\\.[^.]+$", "") : "Imported");

        // Parse header
        int voicePtr = readLE16(data, 0);
        int fmCount = data[2] & 0xFF;
        int psgCount = data[3] & 0xFF;
        song.setDividingTiming(data[4] & 0xFF);
        song.setTempo(data[5] & 0xFF);

        // Parse FM channel entries
        int offset = 6;
        int[] fmPointers = new int[fmCount];
        for (int i = 0; i < fmCount; i++) {
            if (offset + 3 >= data.length) break;
            fmPointers[i] = readLE16(data, offset);
            // key offset at offset+2, volume offset at offset+3 (ignored for now)
            offset += 4;
        }

        // Parse PSG channel entries
        int[] psgPointers = new int[psgCount];
        for (int i = 0; i < psgCount; i++) {
            if (offset + 5 >= data.length) break;
            psgPointers[i] = readLE16(data, offset);
            // key offset, volume offset, mod env, instrument (ignored for now)
            offset += 6;
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

        // Extract FM track data
        for (int i = 0; i < fmCount && i < FM_CHANNEL_COUNT; i++) {
            int ptr = fmPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                int trackEnd = findTrackEnd(data, ptr);
                int len = trackEnd - ptr;
                if (len > 0) {
                    byte[] trackData = new byte[len];
                    System.arraycopy(data, ptr, trackData, 0, len);
                    pattern.setTrackData(i, trackData);
                }
            }
        }

        // Extract PSG track data
        for (int i = 0; i < psgCount && i < PSG_CHANNEL_COUNT; i++) {
            int ptr = psgPointers[i];
            if (ptr >= 0 && ptr < data.length) {
                int trackEnd = findTrackEnd(data, ptr);
                int len = trackEnd - ptr;
                if (len > 0) {
                    byte[] trackData = new byte[len];
                    System.arraycopy(data, ptr, trackData, 0, len);
                    pattern.setTrackData(FM_CHANNEL_COUNT + i, trackData);
                }
            }
        }

        song.getPatterns().add(pattern);

        // Single order row pointing to pattern 0
        int[] orderRow = new int[Pattern.CHANNEL_COUNT];
        song.getOrderList().add(orderRow);
        song.setLoopPoint(0);

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

    private int readLE16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
