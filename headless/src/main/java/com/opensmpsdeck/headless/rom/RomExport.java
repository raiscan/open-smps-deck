package com.opensmpsdeck.headless.rom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Publishes engine output as a private cartridge image, a BPS patch, or both. */
public final class RomExport {
    public record Warning(String code, String message) {}

    private static final Warning PATCHED_IMAGE = new Warning("ROM_PATCHED_IMAGE",
            "A patched ROM contains copyrighted data from your retail ROM. "
                    + "It is for personal use only. Do not redistribute it.");
    private static final Warning BPS_PATCH = new Warning("BPS_PATCH",
            "BPS is the shareable form and requires a supported clean retail ROM to apply. "
                    + "Any material derived from stock assets used by the song remains subject to the original rights.");

    /** Warnings for the requested outputs, in a stable order. */
    public static List<Warning> warnings(boolean rom, boolean bps) {
        var warnings = new java.util.ArrayList<Warning>();
        if (rom) warnings.add(PATCHED_IMAGE);
        if (bps) warnings.add(BPS_PATCH);
        return List.copyOf(warnings);
    }

    private RomExport() {}

    public record Result<R>(RomRevision revision, Path rom, Path bps, BpsPatch.Stats bpsStats,
                            R report, List<Warning> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Use null for the unrequested output. Both outputs are staged and validated before
     * publishing begins. Each destination is published atomically and independently.
     */
    public static <R> Result<R> write(VerifiedRom clean, RomTarget.PatchedRom<R> patched,
                                      Path rom, Path bps, boolean force) throws IOException {
        if (rom == null && bps == null) throw new IllegalArgumentException("Select a ROM or BPS output.");
        Path romDestination = destination(rom, clean.path());
        Path bpsDestination = destination(bps, clean.path());
        if (romDestination != null && romDestination.equals(bpsDestination)) {
            throw new IllegalArgumentException("ROM and BPS need different output paths.");
        }

        byte[] target = patched.bytes();
        BpsPatch.Stats stats = null;
        try (var romOutput = romDestination == null ? null : new AtomicRomOutput(romDestination, force);
             var bpsOutput = bpsDestination == null ? null : new AtomicRomOutput(bpsDestination, force)) {
            if (romOutput != null) Files.write(romOutput.path(), target);
            if (bpsOutput != null) {
                byte[] source = clean.bytes();
                var patch = BpsPatch.encode(source, target);
                stats = patch.stats();
                Files.write(bpsOutput.path(), patch.bytes());
                if (!Arrays.equals(target, BpsPatch.apply(Files.readAllBytes(bpsOutput.path()), source))) {
                    throw new IOException("BPS verification did not reproduce the patched ROM.");
                }
            }
            if (romOutput != null) romOutput.publish();
            if (bpsOutput != null) bpsOutput.publish();
        }
        return new Result<>(clean.revision(), romDestination, bpsDestination, stats, patched.report(),
                warnings(romDestination != null, bpsDestination != null));
    }

    private static Path destination(Path requested, Path clean) throws IOException {
        if (requested == null) return null;
        Path absolute = requested.toAbsolutePath();
        if (absolute.getFileName() == null) throw new IllegalArgumentException("Expected an output file: " + requested);
        Path destination = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        if (destination.equals(clean)
                || (Files.exists(destination) && Files.exists(clean) && Files.isSameFile(destination, clean))) {
            throw new IllegalArgumentException("Output would overwrite the clean ROM: " + requested);
        }
        return destination;
    }
}
