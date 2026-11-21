package com.opensmpsdeck.project;

/** Identifies the retail bank and song from which a project derives. */
public record RetailSongOrigin(String type, String game, int songId, String bankSha256) {
    public RetailSongOrigin {
        if (!"retail-song".equals(type)) throw new IllegalArgumentException("Unknown project origin: " + type);
        StockRef.validateGame(game);
        StockRef.validateHash(bankSha256);
        if (songId < 1 || songId > 65535) throw new IllegalArgumentException("Invalid retail music ID");
    }
}
