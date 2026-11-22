package com.opensmpsdeck.project;

/** Driver IDs shared by project sources, portable packages and ROM tools. */
public enum TargetGame {
    S1("s1"), S2("s2"), S3K("s3k");

    private final String id;

    TargetGame(String id) { this.id = id; }

    public String id() { return id; }

    public static TargetGame parse(String id) {
        for (var game : values()) {
            if (game.id.equals(id)) return game;
        }
        throw new IllegalArgumentException("Unknown target '" + id + "'. Choose s1, s2 or s3k.");
    }
}
