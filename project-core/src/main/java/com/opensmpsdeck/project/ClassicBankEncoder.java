package com.opensmpsdeck.project;

import com.opensmpsdeck.project.ProjectCompiler.Bank;
import com.opensmpsdeck.project.ProjectCompiler.BankLayout;
import com.opensmpsdeck.project.ProjectCompiler.Encoding;

import java.util.ArrayList;
import java.util.TreeSet;

/** Writes a classic song bank, sharing complete-event compaction with the S3K compiler. */
public final class ClassicBankEncoder {
    private final TargetGame target;
    private final Encoding encoding;
    private final TreeSet<Integer> relocations = new TreeSet<>();
    private final Bank out = new Bank(relocations);

    public ClassicBankEncoder(TargetGame target, Encoding encoding, int fm, int psg, int tempo) {
        this.target = target;
        this.encoding = encoding;
        out.skip(6 + fm * 4 + psg * 6);
        out.set(2, fm);
        out.set(3, psg);
        out.set(4, 1);
        out.set(5, tempo);
    }

    public void pointer(int offset) {
        if (target == TargetGame.S1) {
            relocations.add(offset); // S1 header words are unsigned offsets from the header.
            out.set(offset, (int) (out.size() >>> 8));
            out.set(offset + 1, (int) out.size());
        } else {
            out.word(offset, out.size());
        }
    }

    public Track track(int headerOffset) {
        pointer(headerOffset);
        return new Track(new TrackCompactor(out, encoding == Encoding.COMPACT, target));
    }

    public void voiceBytes(byte[] bytes) {
        out.putBytes(bytes);
    }

    public BankLayout finish() {
        if (out.size() > ProjectCompiler.MAX_BANK_BYTES)
            throw new ProjectCompiler.BankLimitException(out.size(), target, encoding);
        return new BankLayout(out.bytes(), new ArrayList<>(relocations));
    }

    public static final class Track {
        private final TrackCompactor encoder;

        private Track(TrackCompactor encoder) {
            this.encoder = encoder;
        }

        public void flag(int... bytes) {
            for (int value : bytes) encoder.put(value);
        }

        public void state(NativeCommand command, int argument) {
            encoder.state(command, argument);
        }

        public void event(int note, int duration) {
            encoder.event(note, duration);
        }

        public void hold() {
            state(NativeCommand.HOLD, 0);
            flag(0xE7);
        }

        public void rest(long ticks) {
            int full = (int) (ticks / 127);
            if (full > 0) encoder.repeat(full, () -> event(0x80, 127));
            int tail = (int) (ticks % 127);
            if (tail > 0) event(0x80, tail);
        }

        public void finish() {
            encoder.finish();
        }
    }
}
