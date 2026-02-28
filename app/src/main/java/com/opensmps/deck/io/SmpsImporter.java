package com.opensmps.deck.io;

import com.opensmps.deck.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Imports raw SMPS binary files into Song models.
 * Works with SMPSPlay .bin rips and exported files.
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
     * Find the end of a track by scanning for track-end markers (F2) or jumps (F4).
     * Returns the offset AFTER the terminal command and its parameters.
     */
    private int findTrackEnd(byte[] data, int start) {
        int pos = start;
        while (pos < data.length) {
            int cmd = data[pos] & 0xFF;

            if (cmd == 0xF2) {
                return pos + 1; // F2 = track end, include it
            }
            if (cmd == 0xF4) {
                return pos + 3; // F4 + 2-byte pointer = jump (loop back)
            }

            // Skip coordination flags with parameters
            if (cmd >= 0xE0 && cmd <= 0xFF) {
                pos++; // skip the flag byte
                pos += getCoordFlagParamLength(cmd);
                continue;
            }

            // Note or duration byte
            pos++;
        }
        return data.length;
    }

    /**
     * Get the parameter byte count for a coordination flag.
     */
    private int getCoordFlagParamLength(int cmd) {
        return switch (cmd) {
            case 0xE0 -> 1; // Pan
            case 0xE1 -> 1; // Set voice
            case 0xE2 -> 1; // Comm data
            case 0xE3 -> 1; // Mod speed env (S3K)
            case 0xE4 -> 1; // PSG instrument
            case 0xE5 -> 1; // PSG detune
            case 0xE6 -> 1; // Note cut
            case 0xE7 -> 0; // Tie
            case 0xE8 -> 1; // PSG noise
            case 0xE9 -> 1; // Key offset
            case 0xEA -> 1; // Detune
            case 0xEB -> 1; // Decay
            case 0xEC -> 0; // FM6 DAC toggle
            case 0xED -> 1; // Tempo change
            case 0xEE -> 1; // Modulation
            case 0xEF -> 1; // Volume offset
            case 0xF0 -> 4; // Enable modulation (4 params)
            case 0xF1 -> 0; // Disable modulation
            case 0xF2 -> 0; // Track end (shouldn't reach here)
            case 0xF3 -> 3; // Loop (counter + 2-byte ptr)
            case 0xF4 -> 2; // Jump (2-byte ptr) - shouldn't reach here
            case 0xF5 -> 2; // Call (2-byte ptr)
            case 0xF6 -> 0; // Return
            case 0xF7 -> 1; // Loop counter decrement
            default -> 0;   // Unknown, assume no params
        };
    }

    private int readLE16(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
