package com.opensmpsdeck.headless;

import java.util.Set;

/** Broad names of the locked-on S3K DAC slots, including its kick and snare hybrids. */
public enum S3kDacFamily {
    KICK, SNARE, OTHER;

    private static final Set<Integer> KICKS = Set.of(0x86, 0x9d, 0xa1, 0xa4, 0xa7, 0xa8,
            0xb4, 0xb5, 0xb7, 0xb8, 0xb9, 0xbd, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4);
    private static final Set<Integer> SNARES = Set.of(0x81, 0x87, 0x8f, 0x94, 0x95, 0x96, 0x97,
            0x9b, 0x9f, 0xa6, 0xab, 0xb2, 0xb3, 0xbc);

    public static S3kDacFamily slot(int slot) {
        if (KICKS.contains(slot)) return KICK;
        if (SNARES.contains(slot)) return SNARE;
        return OTHER;
    }
}
