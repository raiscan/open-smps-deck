package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.opensmpsdeck.headless.rom.RomExport;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.RomTarget;
import com.opensmpsdeck.headless.rompatch.RomPatch;
import com.opensmpsdeck.headless.rompatch.S1RomTarget;
import com.opensmpsdeck.headless.rompatch.S3kRomTarget;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Shared Test in game export for Studio snapshots and headless project/package inputs. */
public final class TestInGame {
    private static final Gson JSON = new Gson();
    private static final Map<RomGame, RomTarget<?, ?, ?>> TARGETS = Map.of(
            RomGame.S1, S1RomTarget.TARGET, RomGame.S3K, S3kRomTarget.TARGET);

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

    public static RomGame game(StudioProject project) {
        return game(project.mode());
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
        // TODO(#81): Resolve/compile the S1 portable bank before exposing the cartridge engine.
        return game != null && game != RomGame.S1 && TARGETS.containsKey(game);
    }

    static void requireAvailable(RomGame game) {
        if (!available(game)) throw unavailable(game.id());
    }

    private static IllegalArgumentException unavailable(String id) {
        return new IllegalArgumentException("Test in game for " + id + " is not available yet.");
    }

    public static JsonObject export(RomLibrary library, Path source, Path rom, Outputs outputs,
                                    RomPatch.Options options) throws IOException {
        outputs.protectSource(source);
        var input = RomPatchInput.load(source);
        return write(library, input, rom, outputs, options);
    }

    public static JsonObject export(RomLibrary library, StudioProject project, Path source, Outputs outputs,
                                    RomPatch.Options options) throws IOException {
        outputs.protectSource(source);
        var input = RomPatchInput.compile(project);
        return write(library, input, null, outputs, options);
    }

    private static JsonObject write(RomLibrary library, RomPatchInput input, Path rom, Outputs outputs,
                                    RomPatch.Options options) throws IOException {
        var target = S3kRomTarget.TARGET;
        var clean = library.resolve(input.game(), rom);
        var patched = target.patch(clean, input.song(), options);
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
