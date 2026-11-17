package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.CompiledProject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Source compilation and pinned S3K playback, independent of JavaFX and audio devices. */
public final class Main {
    private Main() {}

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
                usage(out); return 0;
            }
            if (args.length == 0) throw new IllegalArgumentException("expected a command");
            switch (args[0]) {
                case "validate-project" -> {
                    requireArguments(args, 2);
                    Path input = Path.of(args[1]);
                    var project = ProjectFiles.load(input).project();
                    var compiled = ProjectCompiler.compileWithAssets(project);
                    byte[] song = S3kBinary.validateAndCopy(compiled);
                    out.println("Valid S3K project \"" + project.name() + "\": " + project.sequences().size()
                            + " sequences, " + project.clips().size() + " clips, " + song.length
                            + " compiled bytes (" + input + ")");
                }
                case "compile-project" -> {
                    requireArguments(args, 3);
                    Path input = Path.of(args[1]), output = Path.of(args[2]);
                    requireDifferentFiles(input, output);
                    var compiled = ProjectCompiler.compileWithAssets(ProjectFiles.load(input).project());
                    if (compiled.requiresAssets()) throw new IllegalArgumentException("Project declares envelope or DAC sample assets; use compile-package for a complete export");
                    byte[] song = S3kBinary.validateAndCopy(compiled.song());
                    writeNewBinary(output, song);
                    out.println("Compiled S3K project to " + output + " (" + song.length + " bytes)");
                }
                case "compile-package" -> {
                    requireArguments(args, 3);
                    Path input = Path.of(args[1]), output = Path.of(args[2]);
                    requireDifferentFiles(input, output);
                    ProjectPackage.write(ProjectFiles.load(input).project(), output);
                    out.println("Compiled S3K project and complete resources to " + output);
                }
                case "demo-project" -> {
                    requireArguments(args, 2);
                    Path output = Path.of(args[1]);
                    ProjectFiles.save(output, DemoProject.create(), null);
                    out.println("Created demo project " + output
                            + "; use validate-project, compile-project or render-project to work with it");
                }
                case "demo", "render", "render-project" -> render(args, out);
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            }
            return 0;
        } catch (FileAlreadyExistsException failure) {
            return error(err, "output already exists: " + failure.getFile());
        } catch (IllegalArgumentException | IOException failure) {
            return error(err, failure.getMessage());
        }
    }

    private static void render(String[] args, PrintStream out) throws IOException {
        boolean demo = args[0].equals("demo");
        int positional = demo ? 2 : 3;
        if (args.length != positional && args.length != positional + 2) {
            throw new IllegalArgumentException("wrong number of arguments for " + args[0]);
        }
        int presentations = 120;
        if (args.length == positional + 2) {
            if (!args[positional].equals("--frames")) throw new IllegalArgumentException("expected --frames N");
            try {
                presentations = Integer.parseInt(args[positional + 1]);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("--frames must be an integer from 1 to 36000");
            }
        }
        S3kRenderer.validatePresentations(presentations);
        Path output = Path.of(args[demo ? 1 : 2]);
        CompiledProject song;
        if (demo) {
            song = new CompiledProject(S3kDemo.song(), List.of());
        } else {
            Path input = Path.of(args[1]);
            requireDifferentFiles(input, output);
            if (args[0].equals("render-project")) {
                var project = ProjectFiles.load(input).project();
                song = ProjectCompiler.compileWithAssets(project);
                if (args.length == positional && project.clips().stream().anyMatch(clip -> clip.lane().equals("DAC")))
                    presentations = Math.max(presentations, ProjectRenderTiming.presentationCount(project));
            } else {
                try (var stream = Files.newInputStream(input)) {
                    song = new CompiledProject(stream.readNBytes(S3kBinary.MAX_BYTES + 1), List.of());
                }
            }
        }
        WaveFile.render(song, output, presentations);
        out.println("Rendered " + presentations + " presentations to " + output
                + " (48 kHz stereo, pinned OpenGGF S3K accurate core)");
    }

    private static void requireArguments(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("wrong number of arguments for " + args[0]);
    }

    private static void requireDifferentFiles(Path input, Path output) throws IOException {
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IllegalArgumentException("input and output must be different files");
        }
    }

    private static void writeNewBinary(Path output, byte[] song) throws IOException {
        // The compiler finishes before CREATE_NEW; malformed sources never leave an output.
        var created = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean complete = false;
        try {
            try (var stream = created) { stream.write(song); }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    private static int error(PrintStream err, String message) {
        err.println("Error: " + message);
        usage(err);
        return 2;
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo OUTPUT.wav [--frames N]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render INPUT.bin OUTPUT.wav [--frames N]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo-project OUTPUT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar validate-project PROJECT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-project PROJECT.json OUTPUT.bin");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-package PROJECT.json OUTPUT.smps.zip");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project PROJECT.json OUTPUT.wav [--frames N]");
        out.println("N: 1..36000 NTSC presentations, default 120; DAC source projects include their natural sample tail by default.");
        out.println("Outputs must not already exist. Projects: strict JSON versions 1–4 with explicit envelope/DAC assets.");
        out.println("Binary input: finite linear S3K data, local FM voices; envelope/DAC dependencies require render-project.");
    }
}
