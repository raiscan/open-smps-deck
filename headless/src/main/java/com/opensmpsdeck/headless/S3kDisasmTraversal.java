package com.opensmpsdeck.headless;

import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

import static com.opensmpsdeck.headless.PortablePackageWriter.word;

/** Structural fallback for instructions outside the playback preflight's supported subset. */
final class S3kDisasmTraversal {
    private static final Sonic3kCoordFlagHandler FLAGS = new Sonic3kCoordFlagHandler();

    @FunctionalInterface
    interface Visitor { void event(int offset, int length, boolean dac); }

    private record State(int offset, boolean dac, boolean literalFrequency) {}

    private S3kDisasmTraversal() {}

    static void walk(byte[] bank, int base, int header, int voiceStart, int voices, Set<Integer> fills,
                     Visitor visitor, IntConsumer relocation) {
        int fm = bank[header + 2] & 255;
        int psg = bank[header + 3] & 255;
        int headerEnd = header + 6 + fm * 4 + psg * 6;
        var excluded = new BitSet(bank.length);
        excluded.set(header, headerEnd);
        if (voiceStart >= 0) excluded.set(voiceStart, voiceStart + voices * 25);
        for (int fill : fills) excluded.set(fill, fill + 25);
        var pending = new ArrayDeque<State>();
        for (int i = 0; i < fm + psg; i++) {
            if (i == 6 && fm == 7) continue;
            int entry = header + 6 + 4 * Math.min(i, fm) + 6 * Math.max(0, i - fm);
            relocation.accept(entry);
            pending.add(new State(word(bank, entry) - base, i == 0, false));
        }
        var visited = new HashSet<State>();
        while (!pending.isEmpty()) {
            var state = pending.removeFirst();
            int at = state.offset();
            if (at < 0 || at >= bank.length || excluded.get(at) || !visited.add(state)) continue;
            int command = bank[at] & 255;
            int size = size(bank, at, state);
            int end = at + size;
            if (size == 0 || end > bank.length || excluded.nextSetBit(at) >= 0 && excluded.nextSetBit(at) < end)
                continue;
            visitor.event(at, size, state.dac());
            int pointer = switch (command) {
                case 0xEB -> at + 2;
                case 0xF6, 0xF8, 0xFC -> at + 1;
                case 0xF7 -> at + 3;
                default -> -1;
            };
            if (pointer >= 0) {
                relocation.accept(pointer);
                pending.add(new State(word(bank, pointer) - base, state.dac(), state.literalFrequency()));
            }
            if (command == 0xE3 || command == 0xF2 || command == 0xF6 || command == 0xF9) continue;
            boolean literal = state.literalFrequency();
            if (command == 0xFD && !state.dac()) literal = bank[at + 1] == 1;
            pending.add(new State(end, state.dac(), literal));
        }
    }

    private static int size(byte[] bank, int at, State state) {
        int command = bank[at] & 255;
        if (command < 0xE0) return state.literalFrequency() ? 4 : 1;
        if (command == 0xEF)
            return at + 1 < bank.length && bank[at + 1] < 0 ? 3 : 2;
        if (command != 0xFF) return 1 + FLAGS.flagParamLength(command);
        if (at + 1 >= bank.length) return 0;
        return switch (bank[at + 1] & 255) {
            case 0, 1, 2, 4 -> 3;
            case 5 -> 6;
            case 6 -> 4;
            case 7 -> 2;
            // FF 03 copies into the stream at runtime. The resulting instructions
            // cannot be determined by this reader. Leave its suffix literal.
            default -> 0;
        };
    }
}
