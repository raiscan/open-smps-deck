package com.opensmpsdeck.headless;

import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.project.SmpsCoordFlags.*;

/**
 * Compares the executed stream of two compiler-authored S3K banks. The bank validator
 * guarantees flat counted loops and single-level calls. Those flags change only the
 * cursor, so packing can differ while the timed events and their chip effects agree.
 */
final class S3kProgramPrefix {
    private static final Sonic3kCoordFlagHandler FLAGS = new Sonic3kCoordFlagHandler();
    private final TrackPair[] tracks;

    static S3kProgramPrefix between(CompiledProject before, CompiledProject after) {
        if (before.nativeProgram() != null || after.nativeProgram() != null
                || !before.samples().equals(after.samples())
                || !agreeOnSharedSlots(before.psgEnvelopes(), after.psgEnvelopes())
                || !agreeOnSharedSlots(before.modulationEnvelopes(), after.modulationEnvelopes())) return null;
        byte[] left = before.song(), right = after.song();
        if (!Arrays.equals(left, 2, 6, right, 2, 6)) return null;
        int oldVoices = word(left, 0), newVoices = word(right, 0);
        if ((oldVoices == 0) != (newVoices == 0)) return null;
        if (oldVoices != 0) {
            int shared = Math.min(left.length - oldVoices, right.length - newVoices);
            if (!Arrays.equals(left, oldVoices, oldVoices + shared, right, newVoices, newVoices + shared)) return null;
        }
        int fm = u8(left, 2), psg = u8(left, 3);
        var tracks = new TrackPair[fm + psg];
        int at = 6;
        for (int i = 0; i < tracks.length; i++) {
            int width = i < fm ? 4 : 6;
            if (!Arrays.equals(left, at + 2, at + width, right, at + 2, at + width)) return null;
            tracks[i] = new TrackPair(new Cursor(left, word(left, at)), new Cursor(right, word(right, at)));
            at += width;
        }
        return new S3kProgramPrefix(tracks);
    }

    private static boolean agreeOnSharedSlots(Map<Integer, List<Integer>> left, Map<Integer, List<Integer>> right) {
        // An equal event can refer only to slots present in both validated banks.
        // Adding a new slot after the prefix leaves the earlier state intact.
        for (var slot : left.entrySet()) {
            var other = right.get(slot.getKey());
            if (other != null && !slot.getValue().equals(other)) return false;
        }
        return true;
    }

    private S3kProgramPrefix(TrackPair[] tracks) { this.tracks = tracks; }

    int trackCount() { return tracks.length; }

    Cursor relocate(int trackIndex, SmpsTrackSnapshot track) {
        var pair = tracks[trackIndex];
        int counter = track.loopCounters()[0];
        int returnAddress = track.returnSp() == 0 ? -1 : track.returnStack()[0];
        while (!pair.before.matches(track.pos(), counter, returnAddress, !track.active())) {
            if (!pair.step()) return null;
        }
        return pair.after;
    }

    boolean equivalent() {
        for (var track : tracks) {
            while (!track.before.stopped) if (!track.step()) return false;
        }
        return true;
    }

    private record TrackPair(Cursor before, Cursor after) {
        boolean step() {
            if (before.stopped) return false;
            int left = before.next(), right = after.next();
            return Arrays.equals(before.bank, left, before.pos, after.bank, right, after.pos);
        }
    }

    static final class Cursor {
        private final byte[] bank;
        int pos;
        int loopCount;
        int returnAddress = -1;
        boolean stopped;

        Cursor(byte[] bank, int pos) {
            this.bank = bank;
            this.pos = pos;
        }

        boolean matches(int pc, int counter, int address, boolean ended) {
            return pos == pc && loopCount == counter && returnAddress == address && stopped == ended;
        }

        /** Returns the start of the next timed event or stop, after any packing flags. */
        int next() {
            while (true) {
                switch (u8(bank, pos)) {
                    case LOOP -> {
                        if (loopCount == 0) loopCount = u8(bank, pos + 2);
                        pos = --loopCount == 0 ? pos + 5 : word(bank, pos + 3);
                    }
                    case CALL -> {
                        returnAddress = pos + 3;
                        pos = word(bank, pos + 1);
                    }
                    case RETURN -> {
                        pos = returnAddress;
                        returnAddress = -1;
                    }
                    default -> { return timedEvent(); }
                }
            }
        }

        private int timedEvent() {
            int start = pos;
            while (true) {
                int command = u8(bank, pos++);
                if (command < 0x80) return start;
                if (command < 0xE0) {
                    // All compiler note/rest events carry a duration. A HOLD
                    // continuation may consist of just its flag and duration.
                    pos++;
                    return start;
                }
                if (command == STOP) {
                    stopped = true;
                    return start;
                }
                pos += command == 0xFF ? switch (u8(bank, pos)) {
                    case 0x05 -> 5;
                    case 0x06 -> 3;
                    default -> throw new IllegalArgumentException("Unexpected compiler meta command");
                } : FLAGS.flagParamLength(command);
            }
        }
    }

    private static int u8(byte[] bytes, int at) { return Byte.toUnsignedInt(bytes[at]); }
    private static int word(byte[] bytes, int at) { return u8(bytes, at) | u8(bytes, at + 1) << 8; }
}
