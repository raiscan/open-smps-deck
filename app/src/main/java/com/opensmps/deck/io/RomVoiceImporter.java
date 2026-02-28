package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.Song;

import java.io.File;
import java.util.*;

/**
 * Scans a directory of SMPS files (.bin, .s3k, .sm2, .smp) and extracts all FM voices
 * with deduplication. Uses {@link SmpsImporter} for parsing.
 */
public class RomVoiceImporter {

    private final SmpsImporter importer = new SmpsImporter();

    /**
     * Scan all SMPS files in a directory and extract deduplicated FM voices.
     */
    public List<ImportableVoice> scanDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return List.of();

        File[] files = directory.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".bin") || lower.endsWith(".s3k")
                    || lower.endsWith(".sm2") || lower.endsWith(".smp");
        });
        if (files == null || files.length == 0) return List.of();

        Map<String, ImportableVoice> uniqueVoices = new LinkedHashMap<>();

        for (File file : files) {
            try {
                Song song = importer.importFile(file);
                String songName = file.getName().replaceAll("\\.[^.]+$", "");

                for (int i = 0; i < song.getVoiceBank().size(); i++) {
                    FmVoice voice = song.getVoiceBank().get(i);
                    byte[] data = voice.getData();
                    String key = bytesToHexKey(data);
                    if (!uniqueVoices.containsKey(key)) {
                        int algorithm = data[0] & 0x07;
                        uniqueVoices.put(key, new ImportableVoice(songName, i, data, algorithm));
                    }
                }
            } catch (Exception e) {
                // Skip unparseable files
            }
        }

        return new ArrayList<>(uniqueVoices.values());
    }

    private static String bytesToHexKey(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
