package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Song;
import java.io.File;
import java.nio.file.Files;

public class DacLevelCheck {
    public static void main(String[] args) throws Exception {
        File f = new File("../docs/SMPS-rips/Sonic The Hedgehog 2/2-01 Emerald Hill Zone.sm2");
        if (!f.exists()) f = new File("docs/SMPS-rips/Sonic The Hedgehog 2/2-01 Emerald Hill Zone.sm2");
        Song song = new SmpsImporter().importFile(f);
        System.out.println("DAC samples: " + song.getDacSamples().size());
        for (int i = 0; i < Math.min(5, song.getDacSamples().size()); i++) {
            var s = song.getDacSamples().get(i);
            byte[] d = s.getDataDirect();
            int peak = 0;
            long sum = 0;
            for (byte b : d) { int v = Math.abs((b & 0xFF) - 128); peak = Math.max(peak, v); sum += v; }
            System.out.printf("  sample %d '%s' len=%d rate=%d peakAmp=%d meanAmp=%d%n",
                i, s.getName(), d.length, s.getRate(), peak, d.length > 0 ? sum / d.length : 0);
        }

        for (String mode : new String[]{"DAC only", "FM only", "all"}) {
            boolean[] mutes = new boolean[10];
            if (mode.equals("DAC only")) { for (int i = 0; i < 10; i++) mutes[i] = (i != 5); }
            if (mode.equals("FM only"))  { for (int i = 0; i < 10; i++) mutes[i] = (i == 5 || i >= 6); }
            WavExporter exporter = new WavExporter();
            exporter.setLoopCount(1);
            exporter.setMaxDurationSeconds(8);
            exporter.setFadeEnabled(false);
            exporter.setMutedChannels(mutes);
            File out = File.createTempFile("dac", ".wav");
            exporter.export(song, out);
            byte[] wav = Files.readAllBytes(out.toPath());
            int peak = 0; long sumAbs = 0; int n = 0;
            for (int i = 44; i + 1 < wav.length; i += 2) {
                int s = (short) ((wav[i] & 0xFF) | (wav[i + 1] << 8));
                peak = Math.max(peak, Math.abs(s)); sumAbs += Math.abs(s); n++;
            }
            System.out.printf("%-8s peak=%6d meanAbs=%6d%n", mode, peak, n > 0 ? sumAbs / n : 0);
            out.delete();
        }
    }
}
