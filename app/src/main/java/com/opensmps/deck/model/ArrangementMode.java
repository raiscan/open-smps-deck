package com.opensmps.deck.model;

/**
 * Song arrangement model used by the editor.
 *
 * <p>{@link #HIERARCHICAL} is the primary mode: per-channel chains of phrase
 * references compiled into SMPS bytecode.
 * <p>{@link #STRUCTURED_BLOCKS} enables per-channel block references that can be
 * repeated/reordered independently from raw SMPS bytecode layout.
 */
public enum ArrangementMode {
    STRUCTURED_BLOCKS,
    HIERARCHICAL
}
