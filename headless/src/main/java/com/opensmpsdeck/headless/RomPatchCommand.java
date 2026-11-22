package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.rompatch.RomPatch;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;

/** CLI and MCP entry point for publishing a patched copy. */
public final class RomPatchCommand {
    private RomPatchCommand() {}

    static void run(String[] args, PrintStream out, PrintStream err) throws IOException {
        err.println(RomPatch.WARNING);
        if (args.length < 4)
            throw new IllegalArgumentException("Expected rom-patch PROJECT ROM OUTPUT [--slot ID] [--title-music] [--no-demo] [--loop] [--force]");
        int slot = RomPatch.DEFAULT_SLOT;
        var flags = new HashSet<String>();
        for (int i = 4; i < args.length; i++) {
            String flag = args[i];
            if (!flags.add(flag)) throw new IllegalArgumentException("Duplicate option " + flag);
            switch (flag) {
                case "--slot" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("Missing --slot value");
                    String value = args[i];
                    slot = value.regionMatches(true, 0, "0x", 0, 2)
                            ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
                }
                case "--title-music", "--no-demo", "--loop", "--force" -> {}
                default -> throw new IllegalArgumentException("Unknown option " + flag);
            }
        }
        var result = export(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                new RomPatch.Options(slot, flags.contains("--title-music"), flags.contains("--no-demo"),
                        flags.contains("--loop")), flags.contains("--force"));
        out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result));
    }

    public static JsonObject export(Path source, Path rom, Path output, RomPatch.Options options, boolean force)
            throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        for (Path input : new Path[]{source, rom}) {
            if (destination.equals(input.toAbsolutePath().normalize())
                    || (Files.exists(destination) && Files.isSameFile(input, destination)))
                throw new IllegalArgumentException("ROM patch output must be a separate file from both inputs");
        }
        try (var target = new ExportTarget(destination, force)) {
            if (!Files.isRegularFile(rom)) throw new IOException("Select a regular locked-on S3&K cartridge image");
            byte[] cartridge;
            try (var stream = Files.newInputStream(rom)) {
                cartridge = stream.readNBytes(RomPatch.INPUT_ROM_BYTES + 1);
            }
            var project = RomPatchInput.load(source);
            var result = RomPatch.patch(cartridge, project.compiled(), options, project.relocations());
            Files.write(target.path(), result.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            target.publish();
            result.report().addProperty("output", destination.toString());
            return result.report();
        }
    }
}
