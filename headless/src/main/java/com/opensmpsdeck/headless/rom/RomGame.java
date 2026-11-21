package com.opensmpsdeck.headless.rom;

/** Game IDs match the portable package driver IDs. */
public enum RomGame {
    S1("s1"), S2("s2"), S3K("s3k");

    private final String id;

    RomGame(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static RomGame parse(String id) {
        for (RomGame game : values()) {
            if (game.id.equals(id)) return game;
        }
        throw new IllegalArgumentException("Unknown ROM game '" + id + "'. Choose s1, s2 or s3k.");
    }
}
