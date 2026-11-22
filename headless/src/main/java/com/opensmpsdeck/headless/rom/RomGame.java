package com.opensmpsdeck.headless.rom;

import com.opensmpsdeck.project.TargetGame;

/** Game IDs match the portable package driver IDs. */
public enum RomGame {
    S1(TargetGame.S1), S2(TargetGame.S2), S3K(TargetGame.S3K);

    private final TargetGame target;

    RomGame(TargetGame target) {
        this.target = target;
    }

    public String id() {
        return target.id();
    }

    public static RomGame parse(String id) {
        for (RomGame game : values()) {
            if (game.id().equals(id)) return game;
        }
        throw new IllegalArgumentException("Unknown ROM game '" + id + "'. Choose s1, s2 or s3k.");
    }
}
