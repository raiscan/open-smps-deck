package com.opensmps.deck.model;

/**
 * Song arrangement model used by the editor.
 *
 * <p>{@link #LEGACY_PATTERNS} keeps the current pattern + order-list flow.
 * {@link #STRUCTURED_BLOCKS} enables per-channel block references that can be
 * repeated/reordered independently from raw SMPS bytecode layout.
 */
public enum ArrangementMode {
    LEGACY_PATTERNS,
    STRUCTURED_BLOCKS,
    HIERARCHICAL
}

