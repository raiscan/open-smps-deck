package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsSequencer;

import java.io.File;
import java.util.Arrays;

/**
 * Verifies the HYBRID driver read mode produces byte-identical PCM to
 * SAMPLE_ACCURATE on real songs, and reports the speedup.
 */
public class HybridEquivalence {

    public static void main(String[] args) throws Exception {
        String[] rels = {
            "Sonic The Hedgehog/01 Green Hill Zone.smp",
            "Sonic The Hedgehog 2/2-01 Emerald Hill Zone.sm2",
            "Sonic The Hedgehog 2/2-13 Super Sonic.sm2",
            "Sonic The Hedgehog 3/03 Hydrocity 1.B0BC.s3k",
            "Sonic & Knuckles/1D Bonus - Slot Machine.s3k",
        };
        boolean allOk = true;
        for (String rel : rels) {
            File f = new File("../docs/SMPS-rips/" + rel);
            if (!f.exists()) f = new File("docs/SMPS-rips/" + rel);
            Song song = new SmpsImporter().importFile(f);

            long tA = System.nanoTime();
            short[] a = render(song, SmpsDriver.ReadMode.SAMPLE_ACCURATE);
            long tB = System.nanoTime();
            short[] b = render(song, SmpsDriver.ReadMode.HYBRID);
            long tC = System.nanoTime();

            boolean same = Arrays.equals(a, b);
            allOk &= same;
            System.out.printf("%-55s %s  accurate=%4dms hybrid=%4dms (%.1fx)%n",
                    rel, same ? "IDENTICAL" : "DIFFERS",
                    (tB - tA) / 1_000_000, (tC - tB) / 1_000_000,
                    (double) (tB - tA) / Math.max(1, tC - tB));
            if (!same) {
                for (int i = 0; i < a.length; i++) {
                    if (a[i] != b[i]) {
                        System.out.printf("  first diff at sample %d: %d vs %d%n", i, a[i], b[i]);
                        break;
                    }
                }
            }
        }
        System.out.println(allOk ? "==== ALL IDENTICAL ====" : "==== MISMATCH ====");
        System.exit(allOk ? 0 : 1);
    }

    private static short[] render(Song song, SmpsDriver.ReadMode mode) throws Exception {
        boolean be = song.getSmpsMode() == SmpsMode.S1;
        int fmBase = song.getSmpsMode() == SmpsMode.S2 ? 1 : 0;
        byte[] compiled = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(compiled, fmBase, 0, be);
        data.setVoiceOperatorSwap(song.getSmpsMode() != SmpsMode.S2);
        if (!song.getPsgEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getPsgEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = song.getPsgEnvelopes().get(i).getData();
            data.setPsgEnvelopes(envs);
        }
        if (!song.getModEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getModEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = song.getModEnvelopes().get(i).getData();
            data.setModEnvelopes(envs);
        }

        SmpsDriver driver = new SmpsDriver();
        driver.setReadMode(mode);
        SmpsSequencer seq = new SmpsSequencer(data, null, driver,
                PlaybackEngine.buildConfig(song.getSmpsMode()));
        driver.addSequencer(seq, false);

        int seconds = 20;
        short[] out = new short[44100 * seconds * 2];
        short[] buf = new short[2048];
        int pos = 0;
        while (pos < out.length) {
            driver.read(buf);
            int n = Math.min(buf.length, out.length - pos);
            System.arraycopy(buf, 0, out, pos, n);
            pos += n;
        }
        return out;
    }
}
