package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

final class RomEdits {
    record Range(int start, int end, String purpose) {}

    private final byte[] output;
    private final List<Range> ranges = new ArrayList<>();
    private final BitSet written = new BitSet();

    RomEdits(byte[] rom) { output = rom.clone(); }

    void write(int offset, byte[] bytes, String purpose) {
        int end = Math.addExact(offset, bytes.length);
        int previous = written.nextSetBit(offset);
        if (end > output.length || (previous >= 0 && previous < end))
            throw new IllegalArgumentException("Overlapping or out-of-bounds ROM write at 0x%06X".formatted(offset));
        System.arraycopy(bytes, 0, output, offset, bytes.length);
        written.set(offset, end);
        ranges.add(new Range(offset, end, purpose));
    }

    byte[] bytes() { return output; }
    List<Range> ranges() { return ranges.stream().sorted(Comparator.comparingInt(Range::start)).toList(); }
}
