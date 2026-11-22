package com.opensmpsdeck.headless.rom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Immutable snapshot of a supported clean ROM. The bytes verified are the bytes used by the engine. */
public final class VerifiedRom {
    private static final int MAX_RETAIL_SIZE = Arrays.stream(RomRevision.values())
            .mapToInt(RomRevision::size).max().orElseThrow();

    private final Path path;
    private final RomRevision revision;
    private final byte[] bytes;

    private VerifiedRom(Path path, RomRevision revision, byte[] bytes) {
        this.path = path;
        this.revision = revision;
        this.bytes = bytes;
    }

    public static VerifiedRom read(RomGame game, Path path) throws IOException {
        var rom = read(path);
        if (rom.revision().game() != game) {
            var revision = rom.revision();
            throw new IllegalArgumentException("Expected " + game.id() + " ROM, found " + revision.displayName()
                    + " (" + revision.game().id() + ", " + rom.bytes.length + " bytes, SHA-1 " + revision.sha1() + ").");
        }
        return rom;
    }

    public static VerifiedRom read(Path path) throws IOException {
        Path realPath = path.toRealPath();
        if (!Files.isRegularFile(realPath)) throw new IOException("Expected a regular ROM file: " + path);
        byte[] bytes;
        var digest = RomRevision.sha1Digest();
        long size;
        try (var input = Files.newInputStream(realPath)) {
            bytes = input.readNBytes(MAX_RETAIL_SIZE);
            digest.update(bytes);
            size = bytes.length;
            // Hash oversized input to diagnose it accurately, without buffering the extra bytes.
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                size += count;
                digest.update(buffer, 0, count);
            }
        }

        RomRevision revision = RomRevision.identify(bytes, size, digest.digest());
        return new VerifiedRom(realPath, revision, bytes);
    }

    public Path path() { return path; }
    public RomRevision revision() { return revision; }
    public byte[] bytes() { return bytes.clone(); }
}
