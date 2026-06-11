package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.FmVoice;

import java.util.List;

/** Curated FM voices suggested per GM program family. */
public final class GmVoiceSuggestions {

    private GmVoiceSuggestions() {}

    public static FmVoice forProgram(int program) {
        if (program >= 32 && program <= 39) return fmBass();      // basses
        if (program >= 80 && program <= 87) return squareLead();  // synth leads
        if (program >= 56 && program <= 63) return brass();       // brass
        if (program >= 8 && program <= 15) return bell();         // chromatic perc
        if (program >= 88 && program <= 95) return pad();         // pads
        return squareLead();                                       // default
    }

    public static List<FmVoice> seedBank() {
        return List.of(squareLead(), fmBass(), brass(), bell(), pad());
    }

    private static FmVoice base(String name) {
        return new FmVoice(name, new byte[FmVoice.VOICE_SIZE]);
    }

    // Preset factories are public: Phase 2 (audio.match) uses them as GA seeds
    // and ground-truth voices in its tests.

    /** Algorithm 4 (two stacked pairs), hollow square-ish lead. */
    public static FmVoice squareLead() {
        FmVoice v = base("GM Square Lead");
        v.setAlgorithm(4); v.setFeedback(3);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 6); v.setD2r(op, 2);
            v.setD1l(op, 1); v.setRr(op, 8);
        }
        v.setMul(0, 2); v.setTl(0, 30);   // modulator pair A
        v.setMul(1, 1); v.setTl(1, 8);    // carrier A
        v.setMul(2, 6); v.setTl(2, 45);   // modulator pair B (odd-harmonic colour)
        v.setMul(3, 2); v.setTl(3, 12);   // carrier B
        return v;
    }

    /** Algorithm 0 (serial chain), punchy bass. */
    public static FmVoice fmBass() {
        FmVoice v = base("GM FM Bass");
        v.setAlgorithm(0); v.setFeedback(5);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 12); v.setD2r(op, 4);
            v.setD1l(op, 2); v.setRr(op, 10);
        }
        v.setMul(0, 1); v.setTl(0, 28);
        v.setMul(1, 1); v.setTl(1, 35);
        v.setMul(2, 0); v.setTl(2, 40);   // MUL 0 = ×0.5: sub-octave weight
        v.setMul(3, 1); v.setTl(3, 6);    // carrier
        return v;
    }

    /** Algorithm 4, slower attack, brassy. */
    public static FmVoice brass() {
        FmVoice v = base("GM Brass");
        v.setAlgorithm(4); v.setFeedback(6);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 18); v.setD1r(op, 8); v.setD2r(op, 0);
            v.setD1l(op, 1); v.setRr(op, 8);
        }
        v.setMul(0, 1); v.setTl(0, 26);
        v.setMul(1, 1); v.setTl(1, 10);
        v.setMul(2, 1); v.setTl(2, 30);
        v.setMul(3, 2); v.setTl(3, 12);
        return v;
    }

    /** Algorithm 4, high-ratio modulators, fast decay: bell/keys. */
    public static FmVoice bell() {
        FmVoice v = base("GM Bell");
        v.setAlgorithm(4); v.setFeedback(2);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 14); v.setD2r(op, 6);
            v.setD1l(op, 4); v.setRr(op, 6);
        }
        v.setMul(0, 7); v.setTl(0, 38);   // inharmonic shimmer
        v.setMul(1, 2); v.setTl(1, 10);
        v.setMul(2, 3); v.setTl(2, 42);
        v.setMul(3, 1); v.setTl(3, 14);
        return v;
    }

    /** Algorithm 7 (all carriers), slow attack pad. */
    public static FmVoice pad() {
        FmVoice v = base("GM Pad");
        v.setAlgorithm(7); v.setFeedback(0);
        int[] muls = {1, 2, 1, 4};
        int[] tls = {16, 24, 20, 36};
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 12); v.setD1r(op, 4); v.setD2r(op, 0);
            v.setD1l(op, 0); v.setRr(op, 5);
            v.setMul(op, muls[op]); v.setTl(op, tls[op]);
        }
        return v;
    }
}
