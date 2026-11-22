package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.opensmpsdeck.headless.rom.RomExport;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.headless.rompatch.S1RomPatch;
import com.opensmpsdeck.headless.rompatch.S2RomPatch;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Shared Test in game export for Studio snapshots and headless project/package inputs. */
public final class TestInGame {
    private static final Gson JSON = new Gson();

    private TestInGame() {}

    public record Outputs(Path out, Path bps, boolean force) {
        public Outputs {
            if (out == null && bps == null) throw new IllegalArgumentException("Select at least one output with --out or --bps.");
        }

        void protectSource(Path source) throws IOException {
            for (Path destination : new Path[]{out, bps}) {
                if (destination != null && (destination.toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())
                        || (Files.exists(destination) && Files.isSameFile(source, destination)))) {
                    throw new IllegalArgumentException("Test in game output must be a separate file from the source project.");
                }
            }
        }
    }

    public record Options(Integer slot, boolean titleMusic, boolean noDemo, boolean loop) {
        public Options {
            if (slot != null && !(slot >= 1 && slot <= 0x33 || slot >= 0x81 && slot <= 0x9F))
                throw new IllegalArgumentException("Music ID must be in 0x01–0x33 for S3K, 0x81–0x93 for S1, or 0x81–0x9F for S2");
        }

        RomPatch.Options s3k() {
            return new RomPatch.Options(slot == null ? RomPatch.DEFAULT_SLOT : slot, titleMusic, noDemo, loop);
        }

        S1RomPatch.Options s1() {
            if (loop) throw new IllegalArgumentException("Sonic 1 Test in game does not support --loop.");
            return new S1RomPatch.Options(slot == null ? S1RomPatch.DEFAULT_SLOT : slot, titleMusic, noDemo);
        }

        S2RomPatch.Options s2() {
            return new S2RomPatch.Options(slot == null ? S2RomPatch.DEFAULT_SLOT : slot, titleMusic, noDemo, loop);
        }
    }

    public static RomGame game(StudioProject project) {
        return RomGame.parse(project.target().id());
    }

    static RomGame game(String driver) {
        String id = driver.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "s1" -> RomGame.S1;
            case "s2" -> RomGame.S2;
            case "s3k" -> RomGame.S3K;
            default -> throw unavailable(id);
        };
    }

    public static boolean available(RomGame game) {
        return game != null;
    }

    private static IllegalArgumentException unavailable(String id) {
        return new IllegalArgumentException("Test in game for " + id + " is not available yet.");
    }

    public static JsonObject export(RomLibrary library, Path source, Path rom, Outputs outputs,
                                    RomPatch.Options options) throws IOException {
        return export(library, source, rom, outputs, null,
                new Options(options.slot(), options.titleMusic(), options.noDemo(), options.loop()));
    }

    public static JsonObject export(RomLibrary library, StudioProject project, Path source, Outputs outputs,
                                    RomPatch.Options options) throws IOException {
        return export(library, project, source, outputs, null,
                new Options(options.slot(), options.titleMusic(), options.noDemo(), options.loop()));
    }

    public static JsonObject export(RomLibrary library, Path source, Path rom, Outputs outputs,
                                    RomGame driver, Options options) throws IOException {
        outputs.protectSource(source);
        return write(library, RomPatchInput.load(source, driver), rom, outputs, options);
    }

    public static JsonObject export(RomLibrary library, StudioProject project, Path source, Outputs outputs,
                                    RomGame driver, Options options) throws IOException {
        outputs.protectSource(source);
        return write(library, RomPatchInput.compile(project, driver), null, outputs, options);
    }

    private static JsonObject write(RomLibrary library, RomPatchInput input, Path rom, Outputs outputs,
                                    Options options) throws IOException {
        // Validate game-specific IDs before resolving or opening a cartridge.
        switch (input.game()) {
            case S1 -> options.s1();
            case S2 -> options.s2();
            case S3K -> options.s3k();
        }
        var clean = library.resolve(input.game(), rom);
        var patched = input.song().patch(clean, options);
        var written = RomExport.write(clean, patched, outputs.out(), outputs.bps(), outputs.force());

        var result = new JsonObject();
        result.addProperty("revision", written.revision().displayName());
        if (written.rom() != null) result.addProperty("out", written.rom().toString());
        if (written.bps() != null) {
            result.addProperty("bps", written.bps().toString());
            result.add("bpsStats", JSON.toJsonTree(written.bpsStats()));
        }

        var warnings = new JsonArray();
        written.warnings().forEach(warning -> warnings.add(warning.message()));
        var report = written.report();
        var engineWarnings = report.remove("warnings").getAsJsonArray();
        engineWarnings.remove(new JsonPrimitive(RomPatch.WARNING));
        engineWarnings.forEach(warnings::add);
        result.add("report", report);
        result.add("warnings", warnings);
        return result;
    }
}
