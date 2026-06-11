package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import com.opensmpsdeck.io.midi.MidiReader;
import com.opensmpsdeck.io.midi.MidiStem;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic harness for the voice-matching quality investigation.
 * Prints evidence; makes no assertions beyond sanity. Run with:
 *   mvn test -pl app -Dtest=DiagVoiceMatch
 */
class DiagVoiceMatch {

    private static final String STEMS = "C:\\Users\\farre\\Downloads\\Like We (Chiptune Remix) Stems";

    private static double midiHz(int pitch) {
        return 440.0 * Math.pow(2, (pitch - 69) / 12.0);
    }

    /** Dominant fundamental via 8192-point FFT peak (30..3000 Hz), from the sustain. */
    private static double measureFundamental(float[] audio, int sampleRate) {
        int n = 8192;
        int start = Math.min((int) (0.08 * sampleRate), Math.max(0, audio.length - n));
        if (audio.length < n) return -1;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re[i] = audio[start + i] * hann;
        }
        Fft.transform(re, im);
        double binHz = (double) sampleRate / n;
        int lo = (int) (30 / binHz), hi = (int) (3000 / binHz);
        int peak = lo;
        double max = 0;
        for (int b = lo; b <= hi; b++) {
            double mag = Math.hypot(re[b], im[b]);
            if (mag > max) { max = mag; peak = b; }
        }
        return peak * binHz;
    }

    private static double rms(float[] a) {
        double s = 0;
        for (float v : a) s += v * v;
        return Math.sqrt(s / Math.max(1, a.length));
    }

    private static String voiceSummary(FmVoice v) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("algo=%d fb=%d", v.getAlgorithm(), v.getFeedback()));
        for (int op = 0; op < 4; op++) {
            sb.append(String.format(" | op%d mul=%d dt=%d tl=%d ar=%d d1r=%d d1l=%d rr=%d",
                    op, v.getMul(op), v.getDt(op), v.getTl(op), v.getAr(op),
                    v.getD1r(op), v.getD1l(op), v.getRr(op)));
        }
        return sb.toString();
    }

    @Test
    void rendererPitchProbe() {
        Locale.setDefault(Locale.US);
        var renderer = new CandidateRenderer();
        System.out.println("=== Renderer pitch probe (squareLead) ===");
        for (int pitch : new int[]{36, 48, 60, 72, 84}) {
            float[] audio = renderer.render(GmVoiceSuggestions.squareLead().getData(),
                    pitch, 0.6, 0.0);
            double measured = measureFundamental(audio, 44100);
            System.out.printf("MIDI %d: expected %.1f Hz, measured %.1f Hz (ratio %.3f)%n",
                    pitch, midiHz(pitch), measured, measured / midiHz(pitch));
        }
    }

    @Test
    void stemTargetAndSearchProbe() throws Exception {
        Locale.setDefault(Locale.US);
        File dir = new File(STEMS);
        assumeTrue(dir.isDirectory(), "stems folder not present");

        for (String stemName : new String[]{"Bass", "Synth"}) {
            File mid = new File(dir, "Like We (Chiptune Remix) (" + stemName + ").mid");
            File wav = new File(dir, "Like We (Chiptune Remix) (" + stemName + ").wav");
            assumeTrue(mid.isFile() && wav.isFile());

            System.out.println("\n=== Stem: " + stemName + " ===");
            MidiStem stem = MidiReader.read(mid);
            float[] audio = WavStemReader.readMono44k(wav);
            var map = new TickTimeMapper(stem.ppq(), stem.tempoMap());
            var notes = stem.tracks().get(0).notes();
            var windows = MonophonicWindowFinder.find(notes, map, 8);
            System.out.println("notes=" + notes.size() + " candidate windows=" + windows.size());

            List<FmPatchSearch.Target> targets = new ArrayList<>();
            for (var w : windows) {
                int start = (int) (w.startSec() * 44100);
                int len = (int) (w.lengthSec() * 44100);
                if (start + len > audio.length) continue;
                float[] slice = new float[len];
                System.arraycopy(audio, start, slice, 0, len);
                int onset = WindowValidator.findOnset(slice, 44100);
                if (onset > 0 && onset <= 0.15 * 44100 && len - onset >= 0.2 * 44100) {
                    slice = java.util.Arrays.copyOfRange(slice, onset, len);
                }
                double expHz = midiHz(w.midiPitch());
                var v = WindowValidator.validate(slice, 44100, expHz);
                System.out.printf("window @%.2fs len=%.2fs pitch=%d (%.1f Hz) onsetTrim=%dms -> %s%n",
                        w.startSec(), w.lengthSec(), w.midiPitch(), expHz, onset * 1000 / 44100,
                        v.usable()
                                ? String.format("ACCEPT anchored=%.1f Hz harm=%.2f", v.anchoredHz(), v.harmonicity())
                                : "REJECT " + v.reason());
                if (!v.usable() || targets.size() >= 3) continue;
                var mod = ModulationDetector.measure(slice, 44100, v.anchoredHz());
                System.out.printf("  modulation: depth=%.0f cents rate=%.1f Hz%s%n",
                        mod.depthCents(), mod.rateHz(), mod.significant() ? "  << SIGNIFICANT" : "");
                SpectralTarget t = SpectralTarget.extract(slice, 44100, v.anchoredHz());
                System.out.print("  target harmonics dB:");
                for (double l : t.harmonicLevels()) System.out.printf(" %.0f", l);
                System.out.print("  | valleys:");
                for (int i = 0; i < 5; i++) System.out.printf(" %.0f", t.valleyLevels()[i]);
                System.out.println();
                int anchoredPitch = (int) Math.round(69 + 12 * Math.log(v.anchoredHz() / 440.0) / Math.log(2));
                targets.add(new FmPatchSearch.Target(t, anchoredPitch,
                        Math.min(slice.length / 44100.0, 1.0), mod));
            }
            if (targets.isEmpty()) { System.out.println("NO TARGETS"); continue; }

            // fitness landscape: how well do the 5 seeds separate?
            var renderer = new CandidateRenderer();
            System.out.println("  seed fitness:");
            for (FmVoice seed : GmVoiceSuggestions.seedBank()) {
                System.out.printf("    %-16s %.5f%n", seed.getName(),
                        FmPatchSearch.fitness(seed, targets, renderer));
            }

            // run the real search at default-ish size
            var cfg = new FmPatchSearch.Config(32, 15, Long.MAX_VALUE, 1234L, 3);
            var results = FmPatchSearch.search(targets, GmVoiceSuggestions.seedBank(),
                    cfg, () -> 0L, g -> {});
            for (int i = 0; i < Math.min(2, results.size()); i++) {
                var r = results.get(i);
                System.out.printf("  winner[%d] score=%.5f %s%n", i, r.score(), voiceSummary(r.voice()));
                // tail/ringing check: render with key-off tail, measure tail vs sustain RMS
                float[] rend = renderer.render(r.voice().getData(), targets.get(0).midiPitch(),
                        0.5, 0.5);
                float[] sus = new float[8820];
                float[] tail = new float[8820];
                System.arraycopy(rend, (int) (0.3 * 44100), sus, 0, 8820);
                System.arraycopy(rend, (int) (0.78 * 44100), tail, 0, 8820);
                System.out.printf("    sustainRMS=%.4f tailRMS(0.28s after keyoff)=%.4f ratio=%.2f%n",
                        rms(sus), rms(tail), rms(tail) / Math.max(1e-9, rms(sus)));
            }
        }
    }
}
