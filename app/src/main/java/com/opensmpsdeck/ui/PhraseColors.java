package com.opensmpsdeck.ui;

import javafx.scene.paint.Color;

/**
 * Shared phrase color palette. Used by both SongView and
 * TrackerGrid (unrolled mode) for consistent phrase background tints.
 */
public final class PhraseColors {

    private static final Color[] PALETTE = {
        Color.web("#3a6b8a"), Color.web("#6b3a8a"), Color.web("#3a8a6b"),
        Color.web("#8a6b3a"), Color.web("#8a3a5a"), Color.web("#5a8a3a"),
        Color.web("#3a5a8a"), Color.web("#8a5a3a"), Color.web("#5a3a8a"),
        Color.web("#3a8a5a"), Color.web("#8a3a3a"), Color.web("#3a8a8a")
    };

    private PhraseColors() {}

    /** Get the color for a phrase ID. Deterministic and wraps around. */
    public static Color forPhraseId(int phraseId) {
        return PALETTE[Math.abs(phraseId) % PALETTE.length];
    }
}
