package com.opensmpsdeck.project;

/**
 * Driver IDs shared by project sources, portable packages and ROM tools.
 *
 * <p>A target names where a song is going. Its {@linkplain #rulebook() rulebook} names the
 * driver whose rules, limits and bytecode it follows. Candescence is its own target but
 * follows the stock S3K rulebook for now.
 */
public enum TargetGame {
    S1("s1"), S2("s2"), S3K("s3k"), CANDESCENCE("candescence");

    private final String id;

    TargetGame(String id) { this.id = id; }

    public String id() { return id; }

    /** The retail driver whose rules this target follows. */
    public TargetGame rulebook() {
        return this == CANDESCENCE ? S3K : this;
    }

    /** True when this target compiles and plays with the S3K driver rules. */
    public boolean s3kRules() { return rulebook() == S3K; }

    /** True for targets that are retail Sonic games with ROMs and disassemblies. */
    public boolean retail() { return rulebook() == this; }

    public static TargetGame parse(String id) {
        for (var game : values()) {
            if (game.id.equals(id)) return game;
        }
        throw new IllegalArgumentException("Unknown target '" + id + "'. Choose s1, s2, s3k or candescence.");
    }
}
