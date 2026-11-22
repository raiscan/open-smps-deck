package com.opensmpsdeck.headless.rom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Retail revisions accepted for title-music patching, identified by full-file SHA-1. */
public enum RomRevision {
    S1_REV01(RomGame.S1, "Sonic 1 World REV01", 524_288, "AFE05EEE", "69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B"),
    S2_REV01(RomGame.S2, "Sonic 2 World REV01", 1_048_576, "7B905383", "8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9"),
    S3K_LOCKED_ON(RomGame.S3K, "Sonic 3 & Knuckles (locked-on)", 4_194_304, "63522553", "CFBF98C36C776677290A872547AC47C53D2761D6");

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final RomGame game;
    private final String displayName;
    private final int size;
    private final String crc32;
    private final String sha1;

    RomRevision(RomGame game, String displayName, int size, String crc32, String sha1) {
        this.game = game;
        this.displayName = displayName;
        this.size = size;
        this.crc32 = crc32;
        this.sha1 = sha1;
    }

    public RomGame game() { return game; }
    public String displayName() { return displayName; }
    public int size() { return size; }
    public String crc32() { return crc32; }
    public String sha1() { return sha1; }

    public static RomRevision identify(byte[] bytes) {
        return identify(bytes, bytes.length, sha1Digest().digest(bytes));
    }

    static RomRevision identify(byte[] prefix, long size, byte[] digest) {
        String sha1 = HEX.formatHex(digest);
        for (RomRevision revision : values()) {
            if (revision.size == size && revision.sha1.equals(sha1)) return revision;
        }

        String identification = switch (sha1) {
            case "6DDB7DE1E17E7F6CDB88927BD906352030DAA194" -> "Sonic 1 World REV00 detected (unsupported). ";
            case "14DD06FC3AA19A59A818EA1F6DE150C9061B14D4" -> "Sonic 2 World REV00 detected (unsupported). ";
            default -> "";
        };
        String hint = "";
        String name = "";
        if (hasAscii(prefix, 0x100, "SEGA")) {
            name = headerName(prefix, 0x150);
            if (name.isEmpty()) name = headerName(prefix, 0x120);
        } else if (isSmd(prefix, size)) {
            hint = ". Headered/interleaved SMD image detected. Convert it to raw, deinterleaved .bin or .gen";
        } else if (hasAscii(prefix, 0x300, "SEGA")) {
            name = headerName(prefix, 0x350);
            hint = ". Copier header detected. Convert the image to headerless .bin or .gen";
        }
        String header = name.isEmpty() ? "" : ", header name \"" + name + "\"";
        String supported = Arrays.stream(values()).map(RomRevision::displayName).collect(Collectors.joining(", "));
        throw new IllegalArgumentException(identification + "Unsupported ROM: " + size
                + " bytes, SHA-1 " + sha1 + header + hint + ". Supported revisions: " + supported + ".");
    }

    static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    private static boolean isSmd(byte[] prefix, long size) {
        // SMD uses a 512-byte copier header and swaps odd/even bytes into 8 KiB halves.
        if (size < 512 + 16384 || (size - 512) % 16384 != 0) return false;
        return (prefix[8] == (byte) 0xaa && prefix[9] == (byte) 0xbb && prefix[10] == 6)
                || (hasAscii(prefix, 512 + 0x80, "EA") && hasAscii(prefix, 512 + 8192 + 0x80, "SG"));
    }

    private static boolean hasAscii(byte[] bytes, int start, String text) {
        var expected = text.getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= start + expected.length
                && Arrays.equals(bytes, start, start + expected.length, expected, 0, expected.length);
    }

    private static String headerName(byte[] bytes, int start) {
        if (bytes.length < start + 48) return "";
        String field = new String(bytes, start, 48, StandardCharsets.ISO_8859_1);
        return field.replaceAll("[^ -~]", " ").strip().replaceAll(" +", " ");
    }
}
