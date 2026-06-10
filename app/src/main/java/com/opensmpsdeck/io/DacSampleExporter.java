package com.opensmpsdeck.io;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.Song;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Exports a song's DAC samples to a directory in the SMPSPlay layout:
 * raw unsigned 8-bit PCM files in a {@code DAC/} subfolder plus
 * {@code DAC.ini} (per-sample file + rate) and {@code DefDrum.txt}
 * (note-to-sample mapping). The result can be re-imported by
 * {@link SmpsImporter}'s companion-file loading.
 */
public final class DacSampleExporter {

    private DacSampleExporter() {
    }

    /**
     * Export all DAC samples of the song into the given directory.
     *
     * @param song      source song (samples in note order: 0x81 + index)
     * @param directory target directory; a {@code DAC/} subfolder is created
     */
    public static void export(Song song, File directory) throws IOException {
        List<DacSample> samples = song.getDacSamples();
        if (samples.isEmpty()) {
            throw new IOException("Song has no DAC samples to export");
        }

        File dacDir = new File(directory, "DAC");
        if (!dacDir.isDirectory() && !dacDir.mkdirs()) {
            throw new IOException("Could not create directory: " + dacDir);
        }

        StringBuilder ini = new StringBuilder();
        ini.append("; DAC samples exported by OpenSMPSDeck\n");
        ini.append("; raw unsigned 8-bit PCM, one file per sample\n\n");

        StringBuilder drums = new StringBuilder();
        drums.append("; Note-to-sample mapping exported by OpenSMPSDeck\n");
        drums.append("[Drums]\n");

        for (int i = 0; i < samples.size(); i++) {
            DacSample sample = samples.get(i);
            int id = 0x81 + i;
            String fileName = String.format("DAC_%02X_%s.bin", id, sanitize(sample.getName()));

            Files.write(new File(dacDir, fileName).toPath(), sample.getDataDirect());

            ini.append(String.format("[%02X]%n", id));
            if (sample.getName() != null && !sample.getName().isEmpty()) {
                ini.append("; ").append(sample.getName()).append('\n');
            }
            ini.append("File = DAC\\").append(fileName).append('\n');
            ini.append(String.format("Rate = 0x%02X%n%n", sample.getRate()));

            drums.append(String.format("%02X\tDAC\t%02X\t%02X%n", id, id, sample.getRate()));
        }

        Files.write(new File(directory, "DAC.ini").toPath(),
                ini.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "DefDrum.txt").toPath(),
                drums.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Restrict to filesystem-safe characters. */
    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "Sample";
        String safe = name.replaceAll("[^A-Za-z0-9_\\- ]", "_").trim();
        return safe.isEmpty() ? "Sample" : safe;
    }
}
