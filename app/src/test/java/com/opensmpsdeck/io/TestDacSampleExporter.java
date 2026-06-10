package com.opensmpsdeck.io;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAC export writes the SMPSPlay directory layout (DAC.ini + DefDrum.txt +
 * raw PCM under DAC/) so exported samples can be re-imported as companion
 * files by SmpsImporter.
 */
class TestDacSampleExporter {

    @TempDir
    Path tempDir;

    private static byte[] pcm(int seed, int len) {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) data[i] = (byte) (seed + i);
        return data;
    }

    @Test
    void exportsIniDrumMapAndPcmFiles() throws IOException {
        Song song = new Song();
        song.getDacSamples().add(new DacSample("Kick", pcm(1, 100), 0x17));
        song.getDacSamples().add(new DacSample("Snare", pcm(50, 80), 0x01));

        DacSampleExporter.export(song, tempDir.toFile());

        String ini = Files.readString(tempDir.resolve("DAC.ini"));
        assertTrue(ini.contains("[81]"), "DAC.ini should have a section per sample");
        assertTrue(ini.contains("[82]"));
        assertTrue(ini.contains("Rate = 0x17"));
        assertTrue(ini.contains("Rate = 0x01"));

        String drums = Files.readString(tempDir.resolve("DefDrum.txt"));
        assertTrue(drums.contains("[Drums]"));
        assertTrue(drums.contains("81\tDAC\t81\t17"));

        File dacDir = tempDir.resolve("DAC").toFile();
        File[] files = dacDir.listFiles();
        assertNotNull(files);
        assertEquals(2, files.length, "One PCM file per sample");
    }

    @Test
    void exportRoundTripsThroughTheImporter() throws Exception {
        Song song = new Song();
        song.getDacSamples().add(new DacSample("Kick", pcm(1, 100), 0x17));
        song.getDacSamples().add(new DacSample("Snare", pcm(50, 80), 0x01));
        DacSampleExporter.export(song, tempDir.toFile());

        // A minimal S2 song placed in the export directory picks the samples
        // up as companion files
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); out.write(0); // no voices
        out.write(1);               // 1 FM entry (DAC)
        out.write(0);
        out.write(0x01);
        out.write(0x80);
        out.write(10); out.write(0); // DAC track ptr
        out.write(0); out.write(0);
        out.write(0x81); out.write(0x10);
        out.write(0xF2);
        File smps = tempDir.resolve("test.sm2").toFile();
        Files.write(smps.toPath(), out.toByteArray());

        Song imported = new SmpsImporter().importFile(smps);
        assertEquals(2, imported.getDacSamples().size(),
                "Exported samples should round-trip through companion loading");
        assertArrayEquals(pcm(1, 100), imported.getDacSamples().get(0).getDataDirect());
        assertEquals(0x17, imported.getDacSamples().get(0).getRate());
        assertArrayEquals(pcm(50, 80), imported.getDacSamples().get(1).getDataDirect());
    }

    @Test
    void exportWithoutSamplesFails() {
        Song song = new Song();
        assertThrows(IOException.class,
                () -> DacSampleExporter.export(song, tempDir.toFile()));
    }
}
