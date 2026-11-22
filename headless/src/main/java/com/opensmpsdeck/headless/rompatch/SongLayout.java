package com.opensmpsdeck.headless.rompatch;

import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Reachable S3K instructions and the contiguous pieces required by fall-through. */
final class SongLayout {
    static final int WINDOW = 0x8000;
    private static final Sonic3kCoordFlagHandler FLAGS = new Sonic3kCoordFlagHandler();
    private static final int[] META_LENGTH = {1, 1, 1, 3, 1, 4, 2, 0};

    record Span(int start, int end) {
        int size() { return end - start; }
        boolean contains(int offset) { return start <= offset && offset < end; }
    }

    record Stream(int entry, int start, boolean dac) {}
    private record Cursor(int offset, boolean raw) {}

    private final byte[] bytes;
    private final int base;
    private final boolean exporting;
    private final int[] owner;
    private final int[] lengths;
    private final Set<Integer> alternateVoiceSlots = new TreeSet<>();
    private final BitSet occupied = new BitSet();
    private final BitSet joined = new BitSet();
    private final Set<Integer> pointers = new TreeSet<>();
    private final Set<Integer> dacNotes = new TreeSet<>();
    private final Set<Integer> dacCommands = new TreeSet<>();
    private final Set<Integer> envelopes = new TreeSet<>();
    private final Set<Integer> modulation = new TreeSet<>();
    private final List<Stream> streams = new ArrayList<>();
    private int maxVoice = -1;

    private SongLayout(byte[] bytes, int base, boolean exporting) {
        this.bytes = bytes;
        this.base = base;
        this.exporting = exporting;
        owner = new int[bytes.length];
        lengths = new int[bytes.length];
        Arrays.fill(owner, -1);
    }

    static SongLayout music(byte[] bytes, int base, int header, int voiceCount, boolean exporting) {
        var layout = new SongLayout(bytes, base, exporting);
        layout.readMusic(header, voiceCount);
        return layout;
    }

    static SongLayout sfx(byte[] bytes, int header) {
        var layout = new SongLayout(bytes, WINDOW, false);
        int count = u8(bytes, header + 3);
        layout.claim(header, 4 + count * 6);
        for (int i = 0; i < count; i++) layout.walk(layout.pointer(header + 6 + 6 * i), false, (u8(bytes, header + 5 + 6 * i) & 0x80) != 0);
        return layout;
    }

    private void readMusic(int header, int voiceCount) {
        int fm = u8(bytes, header + 2), psg = u8(bytes, header + 3);
        if (fm < 1 || fm > 7 || psg > 3 || (exporting && fm == 7))
            throw invalid(header, "unsupported channel counts " + fm + "/" + psg);
        claim(header, 6 + fm * 4 + psg * 6);
        int entry = header + 6;
        for (int i = 0; i < fm; i++, entry += 4) {
            int start = pointer(entry);
            streams.add(new Stream(entry, start, i == 0));
            walk(start, i == 0, false);
        }
        for (int i = 0; i < psg; i++, entry += 6) {
            int start = pointer(entry);
            streams.add(new Stream(entry, start, false));
            modulation.add(entry + 4);
            envelopes.add(entry + 5);
            walk(start, false, true);
        }
        int voice = word(bytes, header);
        if (voice == 0 || (base == WINDOW && voice < WINDOW)) return;
        int count = voiceCount < 0 ? maxVoice + 1 : voiceCount;
        if (exporting && (count == 0 || maxVoice >= count))
            throw invalid(header, "voice table does not cover every local voice");
        int offset = pointer(header);
        claim(offset, Math.max(1, count * 25));
    }

    private void walk(int start, boolean dac, boolean psg) {
        var visited = new BitSet();
        var pending = new ArrayDeque<Cursor>();
        pending.add(new Cursor(start, false));
        while (!pending.isEmpty()) {
            var cursor = pending.removeFirst();
            int at = cursor.offset();
            boolean raw = cursor.raw();
            int state = at * 2 + (raw ? 1 : 0);
            if (visited.get(state)) continue;
            visited.set(state);
            int command = u8(bytes, at);
            int length = command >= 0xE0 ? 1 + FLAGS.flagParamLength(command) : raw ? 2 : 1;
            if (command == 0xEB) length = 5; // Retail LOOP_EXIT takes two scalar bytes and a word.
            if (command == 0xFF) {
                int sub = u8(bytes, at + 1);
                if (sub >= META_LENGTH.length) throw invalid(at, "unknown meta command");
                if (exporting && sub >= 1 && sub <= 3)
                    throw invalid(at, "meta command accesses shared hardware state");
                length = 2 + META_LENGTH[sub];
                if (sub == 6) envelopes.add(at + 2);
            }
            if (raw && command < 0xE0 && at + length < bytes.length && u8(bytes, at + length) < 0x80)
                length++;
            if (command == 0xEF && u8(bytes, at + 1) >= 128) length++;
            if (lengths[at] != 0 && lengths[at] != length)
                throw invalid(at, "ambiguous instruction length");
            lengths[at] = length;
            claim(at, length);
            if (dac && !raw && command >= 0x81 && command < 0xE0) dacNotes.add(at);
            if (command == 0xEA) dacCommands.add(at + 1);
            if (command == 0xEF) {
                int voice = u8(bytes, at + 1);
                if (psg) {
                    if (exporting && voice >= 128) throw invalid(at, "alternate PSG envelope operand");
                    envelopes.add(at + 1);
                } else if (voice < 128) {
                    maxVoice = Math.max(maxVoice, voice);
                } else {
                    if (exporting) throw invalid(at, "banked alternate FM voice");
                    alternateVoiceSlots.add(u8(bytes, at + 2) - 0x80);
                }
            }
            if (command == 0xF5 && psg) envelopes.add(at + 1);
            if (command == 0xF1 || command == 0xF4) modulation.add(at + (command == 0xF1 && !psg ? 2 : 1));
            if (command == 0xFD) raw = u8(bytes, at + 1) != 0;

            int operand = switch (command) {
                case 0xEB, 0xF7 -> at + 3;
                case 0xF6, 0xF8, 0xFC -> at + 1;
                default -> -1;
            };
            if (operand >= 0) pending.add(new Cursor(pointer(operand), raw));
            if (command == 0xF2 || command == 0xE3 || command == 0xF6 || command == 0xF9) continue;
            if (at + length >= bytes.length) throw invalid(at, "track falls outside the bank");
            joined.set(at + length);
            pending.add(new Cursor(at + length, raw));
        }
    }

    private void claim(int at, int length) {
        if (at < 0 || at > bytes.length - length) throw invalid(at, "data extends outside the bank");
        for (int i = at; i < at + length; i++) {
            if (owner[i] != -1 && owner[i] != at) throw invalid(i, "overlapping instructions or data");
            owner[i] = at;
        }
        occupied.set(at, at + length);
        joined.set(at + 1, at + length);
    }

    private int pointer(int at) {
        int target = word(bytes, at) - base;
        if (target < 0 || target >= bytes.length) throw invalid(at, "pointer outside the bank");
        pointers.add(at);
        return target;
    }

    List<Span> pieces() {
        var spans = new ArrayList<Span>();
        for (int start = occupied.nextSetBit(0); start >= 0; ) {
            int end = Math.min(joined.nextClearBit(start + 1), occupied.nextClearBit(start));
            spans.add(new Span(start, end));
            start = occupied.nextSetBit(end);
        }
        return spans;
    }

    int lengthAt(int at) { return lengths[at]; }
    boolean instructionAt(int at) { return at >= 0 && at < lengths.length && lengths[at] != 0; }
    Set<Integer> alternateVoiceSlots() { return alternateVoiceSlots; }
    BitSet occupied() { return (BitSet) occupied.clone(); }
    Set<Integer> pointers() { return pointers; }
    Set<Integer> envelopes() { return envelopes; }
    Set<Integer> modulation() { return modulation; }
    Set<Integer> dacNotes() { return dacNotes; }
    Set<Integer> dacCommands() { return dacCommands; }
    List<Stream> streams() { return streams; }

    Set<Integer> stockDacNotes() {
        var notes = new LinkedHashSet<Integer>();
        dacNotes.forEach(at -> notes.add(u8(bytes, at)));
        dacCommands.forEach(at -> {
            int note = u8(bytes, at);
            if (note != 0) notes.add(note | 0x80);
        });
        return notes;
    }

    static int u8(byte[] bytes, int at) {
        if (at < 0 || at >= bytes.length) throw invalid(at, "byte outside the image");
        return Byte.toUnsignedInt(bytes[at]);
    }

    static int word(byte[] bytes, int at) {
        return u8(bytes, at) | u8(bytes, at + 1) << 8;
    }

    static void word(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    static IllegalArgumentException invalid(int at, String reason) {
        return new IllegalArgumentException("SMPS offset 0x%04X: %s".formatted(at, reason));
    }
}
