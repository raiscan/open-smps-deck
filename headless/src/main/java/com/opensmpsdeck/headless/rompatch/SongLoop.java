package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.SongLayout.*;

final class SongLoop {
    record Rewrite(byte[] bytes, int header, Iterable<Integer> relocations) {}
    private record End(int offset, long ticks) {}

    private SongLoop() {}

    static Rewrite rewrite(byte[] song, int base, int header, SongLayout layout, Iterable<Integer> relocations) {
        if (u8(song, header + 4) == 0) throw refusal("song uses a zero timing divider");
        var exits = new TreeMap<Integer, Integer>();
        long length = -1;
        for (var stream : layout.streams()) {
            var end = end(song, base, u8(song, header + 4), layout, stream.start());
            if (end.ticks == 0 || (length >= 0 && length != end.ticks))
                throw refusal("channel durations do not align (%d versus %d ticks)".formatted(length, end.ticks));
            length = end.ticks;
            var other = exits.putIfAbsent(end.offset, stream.start());
            if (other != null && other != stream.start()) throw refusal("different channels share a final stop");
        }

        byte[] rewritten = new byte[song.length + 2 * exits.size()];
        var operands = new TreeSet<Integer>();
        int to = 0;
        for (int from = 0; from < song.length; from++) {
            var target = exits.get(from);
            if (target != null) {
                rewritten[to++] = (byte) 0xF6;
                word(rewritten, to, base + translate(exits, target));
                operands.add(to);
                to += 2;
            } else {
                rewritten[to++] = song[from];
            }
        }
        for (int at : relocations) {
            int newOffset = translate(exits, at);
            word(rewritten, newOffset, base + translate(exits, word(song, at) - base));
            operands.add(newOffset);
        }
        return new Rewrite(rewritten, translate(exits, header), operands);
    }

    private static int translate(Map<Integer, Integer> exits, int offset) {
        return offset + 2 * (int) exits.keySet().stream().filter(end -> end < offset).count();
    }

    private static End end(byte[] song, int base, int divider, SongLayout layout, int start) {
        int pc = start, saved = 0;
        long ticks = 0;
        int[] counters = new int[3];
        var calls = new ArrayDeque<Integer>();
        for (int steps = 0; steps < 1_000_000; steps++) {
            if (!layout.instructionAt(pc)) throw refusal("channel reaches non-instruction data");
            int command = u8(song, pc), next = pc + layout.lengthAt(pc);
            if (command < 0xE0) {
                if (command < 0x80) {
                    saved = (command * divider) & 255;
                    if (saved == 0) saved = 256;
                    ticks += saved;
                } else if (next < song.length && u8(song, next) < 0x80) {
                    pc = next;
                    continue;
                } else {
                    if (saved == 0) throw refusal("channel begins with a reused duration");
                    ticks += saved;
                }
            } else {
                switch (command) {
                    case 0xF2 -> {
                        if (!calls.isEmpty()) throw refusal("channel ends inside a subroutine");
                        if (counters[0] != 0 || counters[1] != 0 || counters[2] != 0)
                            throw refusal("channel has a live loop counter at the stop");
                        return new End(pc, ticks);
                    }
                    case 0xF6 -> next = word(song, pc + 1) - base;
                    case 0xF7, 0xEB -> {
                        int index = u8(song, pc + 1);
                        if (index >= counters.length) throw refusal("invalid loop counter");
                        if (command == 0xF7) {
                            if (counters[index] == 0) counters[index] = u8(song, pc + 2);
                            counters[index] = (counters[index] - 1) & 255;
                            if (counters[index] != 0) next = word(song, pc + 3) - base;
                        } else if (counters[index] == 1) {
                            counters[index] = 0;
                            next = word(song, pc + 3) - base;
                        }
                    }
                    case 0xF8 -> {
                        if (calls.size() == 2) throw refusal("subroutine nesting exceeds the retail stack");
                        calls.push(next);
                        next = word(song, pc + 1) - base;
                    }
                    case 0xF9 -> {
                        if (calls.isEmpty()) throw refusal("return without a call");
                        next = calls.pop();
                    }
                    case 0xE3, 0xFC, 0xFD -> throw refusal("channel uses a nonstandard stop, conditional SFX loop or raw frequency");
                    case 0xFF -> {
                        int sub = u8(song, pc + 1);
                        if (sub == 0 || sub == 4) throw refusal("channel changes the global tempo or timing divider");
                    }
                    default -> {}
                }
            }
            pc = next;
        }
        throw refusal("channel does not reach a finite F2 stop");
    }

    private static IllegalArgumentException refusal(String reason) {
        return new IllegalArgumentException("Cannot enable --loop: " + reason);
    }
}
