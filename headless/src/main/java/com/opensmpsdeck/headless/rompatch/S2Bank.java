package com.opensmpsdeck.headless.rompatch;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.S2Revision.*;
import static com.opensmpsdeck.headless.rompatch.SongLayout.word;

/** Validates S2's reachable stream instructions before rewriting their absolute operands. */
final class S2Bank {
    private record Return(int address, Return outer, int depth) {}
    private record Step(int pc, long counters, int lastCounters, Return returns, int duration, boolean note) {}
    private record Track(int start, int end, long ticks, int volumeCommand, int volumeDelta, int transposeDelta) {}

    private final byte[] source;
    private final int base;
    private final int voices;
    private final int divider;
    private final int headerEnd;
    private final int streamEnd;
    private final int[] owners;
    private final BitSet dacBytes = new BitSet();
    private final TreeSet<Integer> pointers = new TreeSet<>();
    private final TreeSet<Integer> branches = new TreeSet<>();
    private boolean hasJump;
    final TreeSet<Integer> envelopes = new TreeSet<>();
    final TreeSet<Integer> drums = new TreeSet<>();
    private final Track[] tracks;

    static S2Bank read(S2Song song) {
        return new S2Bank(song);
    }

    private S2Bank(S2Song song) {
        source = song.bank();
        if (source.length > WINDOW) throw Placement.oversized(source.length, WINDOW);
        base = song.base();
        voices = song.localVoiceCount();
        require((base == 0 || base == WINDOW) && source.length >= 6, 0, "invalid S2 bank base or header");
        int fm = u8(source, 2), psg = u8(source, 3);
        divider = u8(source, 4);
        require(fm >= 1 && fm <= 7 && psg <= 3 && divider != 0 && u8(source, 5) != 0,
                2, "invalid channel counts or tempo");
        headerEnd = 6 + 4 * fm + 6 * psg;
        int voice = word(source, 0);
        streamEnd = voices == 0 ? source.length : voice - base;
        require(voices >= 0 && voices <= 128 && streamEnd >= headerEnd
                        && source.length - streamEnd == 25 * voices && (voices != 0 || voice == 0),
                0, "invalid local FM table");
        if (voices > 0) pointers.add(0);

        owners = new int[streamEnd];
        Arrays.fill(owners, -1);
        tracks = new Track[fm + psg];
        for (int i = 0; i < tracks.length; i++) {
            int entry = i < fm ? 6 + i * 4 : 6 + fm * 4 + (i - fm) * 6;
            if (i >= fm) {
                require(source[entry + 4] == 0, entry + 4, "PSG modulation envelopes are unsupported");
                envelopes.add(entry + 5);
            }
            tracks[i] = scan(pointer(entry), i == 0, i >= fm, i == fm + 2, fm < 7);
        }
        for (int target : branches)
            require(owners[target] == target, target, "branch must target a reachable instruction");
        if (!song.relocations().isEmpty())
            require(List.copyOf(pointers).equals(song.relocations()), 0, "declared relocations differ from reachable pointers");
    }

    private Track scan(int start, boolean dac, boolean psg, boolean noise, boolean dacAvailable) {
        var visited = new HashMap<Step, Long>();
        int pc = start, duration = 0, maxDepth = 0, maxCounter = -1;
        int volumeDelta = 0, transposeDelta = 0;
        int volumeCommand = psg ? 0xEC : 0xE6;
        long ticks = 0, counters = 0;
        int lastCounters = 0;
        Return returns = null;
        boolean note = false;
        for (int steps = 0; steps < 250_000; steps++) {
            span(pc, 1);
            Long previous = visited.put(new Step(pc, counters, lastCounters, returns, duration, note), ticks);
            if (previous != null) {
                require(previous < ticks && returns == null, pc, "cycle must advance time outside a subroutine");
                return new Track(start, -1, ticks, volumeCommand, volumeDelta, transposeDelta);
            }
            int command = u8(source, pc);
            int length = command < 0xE0 ? 1 : 1 + arity(command, dac, psg, noise);
            span(pc, length);
            int next = pc + length;
            if (command < 0xE0) {
                if (command >= 0x80) {
                    if (dac && command != 0x80) {
                        require(dacAvailable, pc, "DAC is unavailable while FM6 is in use");
                        drums.add(pc);
                    }
                    if (!dac && !psg && command != 0x80) require(voices > 0, pc, "FM notes require a local voice table");
                    note = true;
                    if (next < streamEnd && u8(source, next) < 0x80) {
                        duration = duration(next);
                        next++;
                    }
                } else {
                    duration = duration(pc);
                }
                require(note && duration != 0, pc, "duration or note reuse before the first timed note");
                ticks += duration;
            }
            claim(pc, next, dac);
            switch (command) {
                case 0xE3 -> {
                    require(returns != null, pc, "E3 return without an F8 call");
                    next = returns.address();
                    returns = returns.outer();
                }
                case 0xE6, 0xEC -> volumeDelta = (volumeDelta + u8(source, pc + 1)) & 255;
                case 0xE9 -> transposeDelta = (transposeDelta + u8(source, pc + 1)) & 255;
                case 0xEF -> require(u8(source, pc + 1) < voices, pc, "undefined local FM voice");
                case 0xF2 -> {
                    require(returns == null, pc, "F2 stop inside a subroutine");
                    return new Track(start, pc, ticks, volumeCommand, volumeDelta, transposeDelta);
                }
                case 0xF5 -> envelopes.add(pc + 1);
                case 0xF6 -> {
                    hasJump = true;
                    next = branch(pc + 1);
                }
                case 0xF7 -> {
                    int index = u8(source, pc + 1);
                    maxCounter = Math.max(maxCounter, index);
                    require(maxCounter + 2 * maxDepth < 10, pc, "loop counter and call stack overlap");
                    int shift = (index % 8) * 8;
                    long stored = index < 8 ? counters : lastCounters;
                    int count = (int) (stored >>> shift) & 255;
                    if (count == 0) count = u8(source, pc + 2);
                    count = (count - 1) & 255;
                    long updated = (stored & ~(255L << shift)) | ((long) count << shift);
                    if (index < 8) counters = updated;
                    else lastCounters = (int) updated;
                    int destination = branch(pc + 3);
                    if (count != 0) next = destination;
                }
                case 0xF8 -> {
                    int depth = returns == null ? 1 : returns.depth() + 1;
                    maxDepth = Math.max(maxDepth, depth);
                    require(maxCounter + 2 * maxDepth < 10, pc, "loop counter and call stack overlap");
                    returns = new Return(next, returns, depth);
                    next = branch(pc + 1);
                }
                default -> {}
            }
            pc = next;
        }
        throw invalid(start, "track exceeds the control-flow validation limit");
    }

    // s2.sounddriver.asm:2873–2998; restrict global/SFX-only commands and unsupported package commands.
    private static int arity(int command, boolean dac, boolean psg, boolean noise) {
        int result = switch (command) {
            case 0xE3, 0xF2 -> 0;
            case 0xF6, 0xF8 -> 2;
            case 0xF7 -> 4;
            case 0xE7, 0xF4 -> dac ? -1 : 0;
            case 0xE1, 0xE8, 0xE9 -> dac ? -1 : 1;
            case 0xE0, 0xE6, 0xEF -> !dac && !psg ? 1 : -1;
            case 0xEC, 0xF5 -> psg ? 1 : -1;
            case 0xF3 -> noise ? 1 : -1;
            case 0xF0 -> dac ? -1 : 4;
            default -> -1;
        };
        if (result < 0) throw new IllegalArgumentException("Unsupported S2 stream command 0x%02X".formatted(command));
        return result;
    }

    private int duration(int at) {
        int value = u8(source, at);
        require(value != 0, at, "zero duration");
        int ticks = (value * divider) & 255;
        return ticks == 0 ? 256 : ticks;
    }

    private void span(int at, int length) {
        require(at >= headerEnd && at <= streamEnd - length, at, "instruction outside track data");
    }

    private void claim(int at, int end, boolean dac) {
        for (int i = at; i < end; i++) {
            require(owners[i] == -1 || owners[i] == at, i, "overlapping instructions or branch into an operand");
            require(owners[i] == -1 || dacBytes.get(i) == dac, i, "DAC and tonal instructions cannot share bytes");
            owners[i] = at;
            dacBytes.set(i, dac);
        }
    }

    private int branch(int at) {
        int target = pointer(at);
        branches.add(target);
        return target;
    }

    private int pointer(int at) {
        int target = word(source, at) - base;
        span(target, 1);
        pointers.add(at);
        return target;
    }

    void relocate(byte[] bank) {
        for (int at : pointers) word(bank, at, word(source, at) - base + WINDOW);
    }

    byte[] finish(byte[] bank, boolean loop, boolean extraLife) {
        if (!loop && !extraLife) return bank;
        var replacements = new TreeMap<Integer, byte[]>();
        if (extraLife) {
            require(!loop && divider == 1, 0, "Extra Life requires a finite song with timing divider 1");
            long lastTick = 0;
            for (var track : tracks) {
                require(track.end() >= 0, track.start(), "Extra Life must return to the previous song");
                lastTick = Math.max(lastTick, track.ticks());
            }
            var dac = tracks[0];
            var exit = new ByteArrayOutputStream();
            for (long remaining = lastTick - dac.ticks(); remaining > 0; remaining -= 127) {
                exit.write(0x80);
                exit.write((int) Math.min(remaining, 127));
                if (exit.size() > WINDOW) throw Placement.oversized(exit.size() + bank.length, WINDOW);
            }
            exit.write(0xE4);
            replacements.put(dac.end(), exit.toByteArray());
        } else {
            long length = 0;
            for (var track : tracks) {
                if (track.ticks() == 0) continue; // An immediately stopped, unused channel stays stopped.
                require(track.end() >= 0 && (length == 0 || length == track.ticks()),
                        track.start(), "Cannot enable --loop: active channels need aligned finite durations");
                length = track.ticks();
                var exit = new ByteArrayOutputStream();
                if (track.volumeDelta() != 0) {
                    exit.write(track.volumeCommand());
                    exit.write(-track.volumeDelta());
                }
                if (track.transposeDelta() != 0) {
                    exit.write(0xE9);
                    exit.write(-track.transposeDelta());
                }
                exit.write(0xF6);
                exit.write(track.start());
                exit.write(track.start() >>> 8);
                byte[] jump = exit.toByteArray();
                byte[] previous = replacements.putIfAbsent(track.end(), jump);
                require(previous == null || Arrays.equals(previous, jump), track.end(),
                        "Cannot enable --loop: different channels share a final stop");
            }
            require(length > 0 && !hasJump, 0,
                    "Cannot enable --loop: expected a timed song without existing F6 jumps");
        }
        for (var track : tracks) {
            if (loop && track.ticks() == 0)
                require(!replacements.containsKey(track.end()), track.end(), "An inactive track shares an active loop exit");
        }
        int size = translate(replacements, bank.length);
        if (size > WINDOW) throw Placement.oversized(size, WINDOW);
        var result = new ByteArrayOutputStream(size);
        for (int i = 0; i < bank.length; i++) {
            byte[] instruction = replacements.get(i);
            if (instruction == null) result.write(bank[i]);
            else result.writeBytes(instruction);
        }
        byte[] rewritten = result.toByteArray();
        for (int at : pointers)
            word(rewritten, translate(replacements, at),
                    WINDOW + translate(replacements, word(source, at) - base));
        if (loop) replacements.forEach((at, jump) -> {
            int operand = jump.length - 2;
            word(rewritten, translate(replacements, at) + operand,
                    WINDOW + translate(replacements, word(jump, operand)));
        });
        return rewritten;
    }

    private static int translate(TreeMap<Integer, byte[]> replacements, int at) {
        int offset = at;
        for (var entry : replacements.headMap(at).values()) offset += entry.length - 1;
        return offset;
    }

    private static void require(boolean valid, int at, String reason) {
        if (!valid) throw invalid(at, reason);
    }

    private static IllegalArgumentException invalid(int at, String reason) {
        return new IllegalArgumentException("Invalid S2 bank at 0x%04X: %s".formatted(at, reason));
    }
}
