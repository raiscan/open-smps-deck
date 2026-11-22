package com.opensmpsdeck.project;

import com.opensmpsdeck.project.ProjectCompiler.Bank;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Packs complete timed events, each containing its own duration and any preceding flags. */
final class TrackCompactor {
    private static final int WINDOW = 8192;
    private static final int CANDIDATES = 32;
    private static final int MAX_COUNT = 255;
    private static final int LOOP_BYTES = 5;
    private static final int CALL_BYTES = 3;

    // S3K has two dedicated loop counters and a four-byte return stack.
    // S1/S2 share their counter bytes with the return stack.
    // Use only counter 0 and keep subroutines and loop bodies free of control flow.
    // The flags change only PC/counter/stack. All original timed events and flags,
    // including those affecting note fill, modulation and pan, execute in order.
    record Event(byte[] bytes, int transpose, int volume, boolean hold, boolean relative) {
        @Override public boolean equals(Object other) {
            return other instanceof Event event && transpose == event.transpose
                    && volume == event.volume && hold == event.hold && relative == event.relative
                    && Arrays.equals(bytes, event.bytes);
        }

        @Override public int hashCode() {
            return (31 * transpose + volume) * 31 + Arrays.hashCode(bytes);
        }

        boolean canStart() { return !hold && !relative; }
    }

    private record Subroutine(int start, int length, long offset) {}
    private record Call(long operand, long offset) {}

    private final Bank out;
    private final boolean compact;
    private final TargetGame target;
    private final Bank bodies = new Bank();
    private final List<Call> calls = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private List<Event> capture;
    private int transpose, volume, stateId = 256;
    private boolean hold, relative;

    TrackCompactor(Bank out, boolean compact) {
        this(out, compact, TargetGame.S3K);
    }

    TrackCompactor(Bank out, boolean compact, TargetGame target) {
        this.out = out;
        this.compact = compact;
        this.target = target;
    }

    void put(int value) {
        if (compact || capture != null) pending.write(value);
        else out.put(value);
    }

    void state(NativeCommand command, int argument) {
        if (!compact) return;
        switch (command) {
            case TRANSPOSE -> transpose = argument;
            case VOLUME -> volume = argument;
            case TRANSPOSE_ADD -> { transpose = stateId++; relative = true; }
            case FM_VOLUME, PSG_VOLUME -> { volume = stateId++; relative = true; }
            case HOLD -> hold = true;
            default -> { }
        }
    }

    void event(int note, int duration) {
        if (note >= 0) put(note);
        put(duration);
        completeEvent();
    }

    void heldEvent(int note, int duration) {
        state(NativeCommand.HOLD, 0);
        put(SmpsCoordFlags.HOLD);
        event(note, duration);
    }

    private void completeEvent() {
        if (!compact && capture == null) return;
        var event = new Event(pending.toByteArray(), transpose, volume, hold, relative);
        pending.reset();
        hold = relative = false;
        if (capture != null) capture.add(event);
        else {
            if (events.size() == WINDOW) flush(event.hold);
            events.add(event);
        }
    }

    /**
     * A repeated source iteration has constant bytes after the first pass.
     * Count large homogeneous runs without expanding billions of ticks.
     */
    void repeat(int count, Runnable emit) {
        if (count <= WINDOW || capture != null || pending.size() != 0) {
            for (int i = 0; i < count; i++) emit.run();
            return;
        }
        capture = new ArrayList<>();
        emit.run();
        var block = capture;
        capture = null;
        if (block.isEmpty()) return;
        flush(block.getFirst().hold);
        long size = block.stream().mapToLong(event -> event.bytes.length).sum();
        boolean loopable = compact && block.getFirst().canStart()
                && block.stream().noneMatch(Event::relative);
        // Keep the final iteration inline so a following hold never straddles
        // the end of the loop, even when the next event has yet to be emitted.
        if (loopable) count--;
        while (count > 0 && out.size() < ProjectCompiler.MAX_BANK_BYTES) {
            int repetitions = loopable ? Math.min(MAX_COUNT, count) : 1;
            long target = out.size();
            write(out, block, 0, block.size());
            if (savingsFromLoop(size, repetitions) > 0) loop(target, repetitions);
            else {
                for (int i = 1; i < repetitions; i++) write(out, block, 0, block.size());
            }
            count -= repetitions;
        }
        int groups = loopable ? count / MAX_COUNT : 0;
        int remainder = loopable ? count % MAX_COUNT : count;
        long lastSize = savingsFromLoop(size, remainder) > 0 && loopable ? size + LOOP_BYTES : size * remainder;
        out.skip(groups * (size + LOOP_BYTES) + lastSize);
        if (loopable) write(out, block, 0, block.size());
    }

    void finish() {
        flush(false);
        out.put(SmpsCoordFlags.STOP);
        long base = out.size();
        for (Call call : calls) pointer(call.operand, base + call.offset);
        out.append(bodies);
    }

    private void flush(boolean followingHold) {
        if (events.isEmpty()) return;
        int size = events.size();
        int[] next = new int[size];
        long[] offsets = new long[size + 1];
        var nearest = new HashMap<Event, Integer>();
        for (int i = size - 1; i >= 0; i--) {
            Integer position = nearest.put(events.get(i), i);
            next[i] = position == null ? size : position;
        }
        for (int i = 0; i < size; i++) offsets[i + 1] = offsets[i] + events.get(i).bytes.length;
        int[] periods = new int[size];
        int[] repeats = new int[size];
        boolean[] used = new boolean[size];
        findLoops(next, offsets, periods, repeats, used, followingHold);
        var subroutines = findCalls(next, offsets, used, followingHold);
        for (int i = 0; i < size;) {
            if (periods[i] != 0) {
                long target = out.size();
                write(out, events, i, periods[i]);
                loop(target, repeats[i]);
                i += periods[i] * repeats[i];
            } else if (subroutines[i] != null) {
                var subroutine = subroutines[i];
                out.put(SmpsCoordFlags.CALL);
                if (out.size() < ProjectCompiler.MAX_BANK_BYTES) calls.add(new Call(out.size(), subroutine.offset));
                out.put(0);
                out.put(0);
                i += subroutine.length;
            } else {
                write(out, events, i++, 1);
            }
        }
        events.clear();
    }

    private void findLoops(int[] next, long[] offsets, int[] periods, int[] repeats, boolean[] used, boolean followingHold) {
        int size = events.size();
        for (int start = 0; start < size;) {
            int bestLength = 0, bestRepeats = 0;
            long best = 0;
            if (events.get(start).canStart()) {
                int second = next[start];
                for (int checked = 0; second < size && checked < CANDIDATES; checked++, second = next[second]) {
                    int length = second - start;
                    if (length > size - second) break;
                    if (match(start, second, length, used) < length) continue;
                    int count = 2;
                    while (count < MAX_COUNT && start + (count + 1) * length <= size
                            && match(start, start + count * length, length, used) == length) count++;
                    // Ties use the previous sounding event. Leave that boundary inline.
                    while (count >= 2 && !canEnd(start + length * count, followingHold)) count--;
                    long saving = savingsFromLoop(offsets[second] - offsets[start], count);
                    if (count >= 2 && saving > best) {
                        best = saving;
                        bestLength = length;
                        bestRepeats = count;
                        if (start + length * count == size) break;
                    }
                }
            }
            if (bestLength != 0) {
                periods[start] = bestLength;
                repeats[start] = bestRepeats;
                int end = start + bestLength * bestRepeats;
                Arrays.fill(used, start, end, true);
                start = end;
            } else start++;
        }
    }

    private Subroutine[] findCalls(int[] next, long[] offsets, boolean[] used, boolean followingHold) {
        int size = events.size();
        var subroutines = new Subroutine[size];
        for (int start = 0; start < size; start++) {
            if (used[start] || !events.get(start).canStart()) continue;
            int bestLength = 0, bestOther = 0;
            long best = 2 * CALL_BYTES + 1; // Two call sites plus the return.
            int second = next[start];
            for (int checked = 0; second < size && checked < CANDIDATES; checked++, second = next[second]) {
                // Adjacent occurrences belong to the loop pass.
                int length = match(start, second, second - start - 1, used);
                while (length > 0 && (!canEnd(start + length, followingHold) || !canEnd(second + length, followingHold))) length--;
                long bytes = offsets[start + length] - offsets[start];
                if (bytes > best) {
                    best = bytes;
                    bestLength = length;
                    bestOther = second;
                }
            }
            if (bestLength == 0) continue;
            var subroutine = new Subroutine(start, bestLength, bodies.size());
            write(bodies, events, start, bestLength);
            bodies.put(target == TargetGame.S3K ? SmpsCoordFlags.RETURN : 0xE3);
            markCall(start, subroutine, subroutines, used);
            markCall(bestOther, subroutine, subroutines, used);
            // The representative is now reserved; compare later occurrences only
            // against its immutable event bytes.
            for (int other = next[bestOther]; other < size; other = next[other]) {
                if (match(subroutine.start, other, bestLength, used, false) == bestLength
                        && canEnd(other + bestLength, followingHold)) markCall(other, subroutine, subroutines, used);
            }
        }
        return subroutines;
    }

    private static void markCall(int start, Subroutine subroutine, Subroutine[] subroutines, boolean[] used) {
        subroutines[start] = subroutine;
        Arrays.fill(used, start, start + subroutine.length, true);
    }

    private int match(int first, int second, int maximum, boolean[] used) {
        return match(first, second, maximum, used, true);
    }

    private int match(int first, int second, int maximum, boolean[] used, boolean checkFirst) {
        int length = 0, limit = Math.min(maximum, events.size() - second);
        while (length < limit && (!checkFirst || !used[first + length]) && !used[second + length]) {
            Event left = events.get(first + length), right = events.get(second + length);
            if (left.relative || right.relative || !left.equals(right)) break;
            length++;
        }
        return length;
    }

    private boolean canEnd(int end, boolean followingHold) {
        return end == events.size() ? !followingHold : !events.get(end).hold;
    }

    private static long savingsFromLoop(long length, int count) {
        return length * (count - 1) - LOOP_BYTES;
    }

    private void loop(long target, int count) {
        out.put(SmpsCoordFlags.LOOP);
        out.put(0);
        out.put(count);
        long operand = out.size();
        out.put(0);
        out.put(0);
        pointer(operand, target);
    }

    private void pointer(long operand, long destination) {
        if (target == TargetGame.S1) {
            // The signed big-endian displacement is based at the second operand byte.
            long displacement = destination - (operand + 1);
            out.set(operand, (int) (displacement >> 8));
            out.set(operand + 1, (int) displacement);
        } else {
            out.word(operand, destination);
        }
    }

    private static void write(Bank out, List<Event> events, int start, int length) {
        for (int i = start; i < start + length; i++) out.putBytes(events.get(i).bytes);
    }
}
