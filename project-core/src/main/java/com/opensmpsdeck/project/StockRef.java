package com.opensmpsdeck.project;

import java.util.List;

/** Exact lookup in a stock game resource table. */
public record StockRef(String game, String table, Integer songId, Integer slot, Integer index,
                       Integer note, String sha256) {
    public StockRef {
        validateGame(game);
        validateHash(sha256);
        boolean valid = switch (table) {
            case "global-fm" -> game.equals("s3k") && songId == null && inRange(slot, 0, 127) && index == null && note == null;
            case "local-fm" -> inRange(songId, 1, 65535) && inRange(slot, 0, 255) && index == null && note == null;
            case "psg", "mod" -> songId == null && slot == null && inRange(index, 0, 255) && note == null
                    && (!table.equals("mod") || game.equals("s3k"));
            case "dac" -> songId == null && slot == null && index == null
                    && inRange(note, 0x81, game.equals("s3k") ? 0xC4 : 0xDF);
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Invalid " + game + " stock locator: " + table);
    }

    public String kind() {
        return switch (table) {
            case "local-fm", "global-fm" -> "FM_VOICE";
            case "psg" -> "PSG_VOLUME";
            case "mod" -> "MODULATION";
            case "dac" -> "DAC_SAMPLE";
            default -> throw new AssertionError(table);
        };
    }

    static void validateGame(String game) {
        if (!List.of("s1", "s2", "s3k").contains(game))
            throw new IllegalArgumentException("Unknown stock game: " + game);
    }

    private static boolean inRange(Integer value, int low, int high) {
        return value != null && low <= value && value <= high;
    }

    static void validateHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected lowercase SHA-256");
    }
}
