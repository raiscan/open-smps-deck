package com.opensmpsdeck.model;

import com.opensmps.smps.SmpsCoordFlags;

/**
 * Target SMPS driver variant, affecting tempo mode and sequencer configuration.
 *
 * <p>{@code S1} uses TIMEOUT tempo and PC-relative pointers.
 * {@code S2} uses OVERFLOW2 tempo. {@code S3K} uses OVERFLOW tempo.
 */
public enum SmpsMode {
    /** Sonic 1 (68k SMPS driver). */
    S1,
    /** Sonic 2 (Z80 SMPS driver). */
    S2,
    /** Sonic 3 & Knuckles (Z80 SMPS driver). */
    S3K;

    /** The coordination flag dialect for this driver variant. */
    public SmpsCoordFlags.Dialect dialect() {
        return switch (this) {
            case S1 -> SmpsCoordFlags.Dialect.S1;
            case S2 -> SmpsCoordFlags.Dialect.S2;
            case S3K -> SmpsCoordFlags.Dialect.S3K;
        };
    }
}
