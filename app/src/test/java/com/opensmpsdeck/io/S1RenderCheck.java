package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Song;
import java.io.File;
import java.nio.file.Files;

public class S1RenderCheck {
    public static void main(String[] args) throws Exception {
        String[] names = {
            "Sonic The Hedgehog/01 Green Hill Zone.smp",
            "Sonic The Hedgehog 3/01 Angel Island 1.8000.s3k",
        };
        for (String name : names) {
            File f = new File("../docs/SMPS-rips/" + name);
            if (!f.exists()) f = new File("docs/SMPS-rips/" + name);
            Song song = new SmpsImporter().importFile(f);
            WavExporter exporter = new WavExporter();
            exporter.setLoopCount(1);
            exporter.setMaxDurationSeconds(8);
            exporter.setFadeEnabled(false);
            File out = File.createTempFile("render", ".wav");
            exporter.export(song, out);
            byte[] wav = Files.readAllBytes(out.toPath());
            // Measure peak amplitude over 16-bit samples after the 44-byte header
            int peak = 0; long sumAbs = 0; int n = 0;
            for (int i = 44; i + 1 < wav.length; i += 2) {
                int s = (short) ((wav[i] & 0xFF) | (wav[i + 1] << 8));
                peak = Math.max(peak, Math.abs(s));
                sumAbs += Math.abs(s); n++;
            }
            System.out.printf("%-50s wavBytes=%8d peak=%6d meanAbs=%6d voices=%d%n",
                name, wav.length, peak, n > 0 ? sumAbs / n : 0, song.getVoiceBank().size());
            out.delete();
        }
    }
}
