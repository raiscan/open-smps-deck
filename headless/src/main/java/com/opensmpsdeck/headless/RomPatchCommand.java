package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.RomGame;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

final class RomPatchCommand {
    static final String USAGE = "rom-patch PROJECT [--driver s3k|s2] [--rom PATH] [--out SONG.gen] [--bps SONG.bps]"
            + " [--slot ID] [--title-music] [--no-demo] [--loop] [--force]";

    private RomPatchCommand() {}

    static void run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2 || args[1].startsWith("--")) throw new IllegalArgumentException("Expected " + USAGE);
        Integer slot = null;
        RomGame driver = null;
        Path rom = null, image = null, bps = null;
        var flags = new HashSet<String>();
        for (int i = 2; i < args.length; i++) {
            String flag = args[i];
            if (!flags.add(flag)) throw new IllegalArgumentException("Duplicate option " + flag);
            switch (flag) {
                case "--driver" -> driver = RomGame.parse(value(args, ++i, flag).toLowerCase(Locale.ROOT));
                case "--slot" -> slot = slot(value(args, ++i, flag));
                case "--rom" -> rom = Path.of(value(args, ++i, flag));
                case "--out" -> image = Path.of(value(args, ++i, flag));
                case "--bps" -> bps = Path.of(value(args, ++i, flag));
                case "--title-music", "--no-demo", "--loop", "--force" -> {}
                default -> throw new IllegalArgumentException("Unknown option " + flag);
            }
        }

        var outputs = new TestInGame.Outputs(image, bps, flags.contains("--force"));
        var options = new TestInGame.Options(slot, flags.contains("--title-music"),
                flags.contains("--no-demo"), flags.contains("--loop"));
        var result = TestInGame.export(RomLibrary.userLibrary(), Path.of(args[1]), rom, outputs, driver, options);
        result.getAsJsonArray("warnings").forEach(warning -> err.println(warning.getAsString()));
        out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result));
    }

    /** Route the two cartridge formats without extending the audio/native format parser. */
    static boolean exportAlias(String[] args, PrintStream out, PrintStream err) throws IOException {
        for (int i = 3; i + 1 < args.length; i++) {
            if (!args[i].equals("--format")) continue;
            String format = args[i + 1].toLowerCase(Locale.ROOT);
            if (!format.equals("rom") && !format.equals("bps")) return false;
            var patch = new ArrayList<String>();
            patch.add("rom-patch");
            patch.add(args[1]);
            patch.add(format.equals("rom") ? "--out" : "--bps");
            patch.add(args[2]);
            for (int j = 3; j < args.length; j++) {
                if (j != i && j != i + 1) patch.add(args[j]);
            }
            run(patch.toArray(String[]::new), out, err);
            return true;
        }
        return false;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("--"))
            throw new IllegalArgumentException("Missing " + flag + " value");
        return args[index];
    }

    private static int slot(String value) {
        try {
            return value.regionMatches(true, 0, "0x", 0, 2)
                    ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid music slot '" + value + "'. Use a decimal or 0x-prefixed ID.");
        }
    }
}
