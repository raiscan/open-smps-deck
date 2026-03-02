package com.opensmpsdeck.io;

/**
 * Utility methods for converting between byte arrays and hex strings.
 *
 * <p>The canonical format is space-separated uppercase hex bytes
 * (e.g. {@code "3A 07 1F"}), used by project and voice bank file I/O.
 */
public final class HexUtil {

    private HexUtil() {
        // Utility class
    }

    /**
     * Converts a byte array to a space-separated uppercase hex string.
     *
     * @param data the bytes to encode
     * @return hex string, e.g. {@code "3A 07 1F"}, or empty string for zero-length input
     */
    public static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Parses a space-separated hex string back into a byte array.
     *
     * @param hex the hex string to parse (e.g. {@code "3A 07 1F"})
     * @return the decoded bytes, or an empty array if input is null or blank
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isBlank()) return new byte[0];
        String[] parts = hex.trim().split("\\s+");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return result;
    }
}
