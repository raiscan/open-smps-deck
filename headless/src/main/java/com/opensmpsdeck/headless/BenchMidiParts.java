package com.opensmpsdeck.headless;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.Track;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** MIDI annotation used by the benchmark to preserve overlapping gates within one logical part. */
public final class BenchMidiParts {
    public static final int META_TYPE = 0x7f;
    private static final byte[] TAG = "\u007dOpenSMPSDeck grouped MIDI part".getBytes(StandardCharsets.US_ASCII);

    private BenchMidiParts() {}

    public static byte[] tag() { return TAG.clone(); }

    static boolean grouped(Track track) {
        for (int i = 0; i < track.size() && track.get(i).getTick() == 0; i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == META_TYPE
                    && Arrays.equals(meta.getData(), TAG)) return true;
        }
        return false;
    }
}
