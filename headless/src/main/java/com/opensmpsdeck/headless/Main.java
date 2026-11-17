package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point for the first headless S3K proof, independent of JavaFX and audio devices. */
public final class Main {
    private Main() {}

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
                usage(out); return 0;
            }
            if (args.length < 2) throw new IllegalArgumentException("expected demo or render command");
            boolean demo = args[0].equals("demo");
            if (!demo && !args[0].equals("render")) throw new IllegalArgumentException("unknown command: " + args[0]);
            int positional = demo ? 2 : 3;
            if (args.length != positional && args.length != positional + 2) throw new IllegalArgumentException("wrong number of arguments");
            int presentations = 120;
            if (args.length == positional + 2) {
                if (!args[positional].equals("--frames")) throw new IllegalArgumentException("expected --frames N");
                presentations = Integer.parseInt(args[positional + 1]);
            }
            S3kRenderer.validatePresentations(presentations);
            Path output = Path.of(args[demo ? 1 : 2]);
            byte[] song;
            if (demo) {
                song = S3kDemo.song();
            } else {
                Path input = Path.of(args[1]);
                if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                        || (Files.exists(output) && Files.isSameFile(input, output))) {
                    throw new IllegalArgumentException("input and output must be different files");
                }
                try (var stream = Files.newInputStream(input)) {
                    song = stream.readNBytes(S3kBinary.MAX_BYTES + 1);
                }
            }
            WaveFile.render(song, output, presentations);
            out.println("Rendered " + presentations + " presentations to " + output
                    + " (48 kHz stereo, pinned OpenGGF S3K accurate core)");
            return 0;
        } catch (IllegalArgumentException | IOException failure) {
            err.println("Error: " + failure.getMessage());
            usage(err);
            return 2;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo OUTPUT.wav [--frames N]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render INPUT.bin OUTPUT.wav [--frames N]");
        out.println("N: 1..36000 NTSC presentations, default 120. Outputs must not already exist.");
        out.println("First proof: finite linear S3K data, local FM voices, no external envelopes or DAC samples.");
    }
}
