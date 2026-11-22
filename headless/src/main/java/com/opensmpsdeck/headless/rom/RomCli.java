package com.opensmpsdeck.headless.rom;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

public final class RomCli {
    private static final String USAGE = "rom list | rom set GAME PATH | rom clear GAME (GAME: s1, s2, s3k)";

    private RomCli() {}

    public static void run(String[] args, PrintStream out) throws IOException {
        var library = RomLibrary.userLibrary();
        String operation = args.length > 1 ? args[1] : "";
        switch (operation) {
            case "list" -> {
                requireLength(args, 2);
                for (var entry : library.list()) print(entry, out);
            }
            case "set" -> {
                requireLength(args, 4);
                print(library.set(RomGame.parse(args[2]), Path.of(args[3])), out);
            }
            case "clear" -> {
                requireLength(args, 3);
                var game = RomGame.parse(args[2]);
                library.clear(game);
                out.println("Cleared " + game.id() + " ROM setting.");
            }
            case "--help", "-h" -> {
                requireLength(args, 2);
                usage(out);
            }
            default -> throw new IllegalArgumentException("Expected " + USAGE);
        }
    }

    public static void usage(PrintStream out) {
        out.println("       " + USAGE);
    }

    private static void requireLength(String[] args, int length) {
        if (args.length != length) throw new IllegalArgumentException("Expected " + USAGE);
    }

    private static void print(RomLibrary.Entry entry, PrintStream out) {
        out.print(entry.game() + "\t" + entry.status());
        if (entry.path() != null) out.print("\t" + entry.path());
        if (entry.revision() != null) out.print("\t" + entry.revision());
        if (entry.detail() != null) out.print("\t" + entry.detail());
        out.println();
    }
}
