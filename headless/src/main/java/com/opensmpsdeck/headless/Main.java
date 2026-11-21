package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.bench.BenchCli;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

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
                case "mcp" -> {
                    requireArguments(args, 1);
                    com.opensmpsdeck.headless.mcp.McpServer.run(System.in, out);
                }
                case "catalog" -> CatalogueCli.run(args, out);
                case "notes", "reference" -> ReviewCli.run(args, out);
                case "group" -> GroupCli.run(args, out);
                case "reconstruct" -> ReconstructionCli.run(args, out, err);
                case "bench" -> {
                    return BenchCli.run(args, out, new BenchCli.Hooks(ReconstructionCli::run,
                            S3kRenderer::timelineSequencer, path -> new com.google.gson.Gson().toJsonTree(MidiScore.read(path))));
                }
                case "copy-arrangement" -> {
                    requireArguments(args, 3);
                    Path input = Path.of(args[1]), output = Path.of(args[2]);
                    requireDifferentFiles(input, output);
                    try {
                        var copy = NativeArrangement.editableCopy(ProjectFiles.load(input).project());
                        ProjectFiles.save(output, copy, null);
                        out.printf("Saved editable copy with %d clips to %s%n", copy.clips().size(), output);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Arrangement conversion interrupted", stopped);
                    }
                }
                case "list-rom-songs" -> {
                    requireArguments(args, 2);
                    for (var song : S3kRomImporter.listSongs(Path.of(args[1]))) {
                        out.printf(Locale.ROOT, "0x%02X\t%s%n", song.id(), song.name());
                    }
                }
                case "import-rom-song" -> {
                    requireArguments(args, 4);
                    Path input = Path.of(args[1]), output = Path.of(args[3]);
                    requireDifferentFiles(input, output);
                    int id = parseMusicId(args[2]);
                    var song = S3kRomImporter.readSong(input, id);
                    ProjectFiles.save(output, song, null);
                    out.printf(Locale.ROOT, "Imported 0x%02X %s to %s%n", id, song.name(), output);
                    if (id == 0x2A) out.println("Extra Life restores prior music. Render with --previous-project and --previous-frames.");
                }
                case "validate-project", "validate" -> {
                    requireArguments(args, 2);
                    Path input = Path.of(args[1]);
                    var project = ProjectFiles.load(input).project();
                    var compiled = ProjectCompiler.compileWithAssets(project);
                    byte[] song = S3kBinary.validateAndCopy(compiled);
                    if (compiled.nativeProgram() != null) {
                        out.printf(Locale.ROOT, "Valid native S3K project \"%s\" (%d bank bytes, header %04X)%n",
                                project.name(), song.length,
                                compiled.nativeProgram().bankBase() + compiled.nativeProgram().headerOffset());
                    } else {
                        String groups = project.groupSequences().isEmpty() ? ""
                                : project.groupSequences().size() + " group sequences, ";
                        out.println("Valid S3K project \"" + project.name() + "\": " + project.sequences().size()
                                + " sequences, " + groups + project.clips().size() + " clips, " + song.length
                                + " compiled bytes (" + input + ")");
                    }
                }
                case "inspect-project" -> {
                    requireArguments(args, 2);
                    var compiled = ProjectCompiler.compileWithAssets(ProjectFiles.load(Path.of(args[1])).project());
                    if (compiled.nativeProgram() == null)
                        throw new IllegalArgumentException("inspect-project requires a native bank project");
                    inspect(compiled, out);
                }
                case "compile-project" -> {
                    requireArguments(args, 3);
                    Path input = Path.of(args[1]), output = Path.of(args[2]);
                    requireDifferentFiles(input, output);
                    var compiled = ProjectCompiler.compileWithAssets(ProjectFiles.load(input).project());
                    if (compiled.nativeProgram() != null)
                        throw new IllegalArgumentException("Use compile-package to preserve native bank addresses and resources");
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
                    out.println("Exported S3K bank and declared resources to " + output);
                }
                case "demo-project" -> {
                    requireArguments(args, 2);
                    Path output = Path.of(args[1]);
                    ProjectFiles.save(output, DemoProject.create(), null);
                    out.println("Created demo project " + output
                            + "; use validate-project, compile-project or render-project to work with it");
                }
                case "demo", "render", "render-project" -> render(args, out);
                case "render-compare" -> RenderComparison.cli(args, out);
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            }
            return 0;
        } catch (IllegalArgumentException | IOException failure) {
            String message = failure instanceof FileAlreadyExistsException exists
                    ? "output already exists: " + exists.getFile() : failure.getMessage();
            err.println("Error: " + message);
            if (args.length == 0 || !args[0].equals("reconstruct")) usage(err);
            return 2;
        }
    }

    private static void inspect(CompiledProject compiled, PrintStream out) {
        var analysis = NativeBankAnalysis.inspectForEditing(compiled);
        out.printf(Locale.ROOT, "Native S3K bank %04X, header %04X, %d bytes%n",
                compiled.nativeProgram().bankBase(), analysis.headerAddress(), analysis.bank().length);
        if (analysis.requiresPreviousMusic()) {
            out.println("Playback requires --previous-project and --previous-frames.");
        }
        out.println("Address  Bytes              Meaning");
        for (var event : analysis.events()) {
            var bytes = new StringBuilder();
            for (int value : event.bytes()) {
                if (!bytes.isEmpty()) bytes.append(' ');
                bytes.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
            }
            out.printf(Locale.ROOT, "%04X     %-18s %s%n", event.address(), bytes, event.meaning());
        }
    }

    private static void render(String[] args, PrintStream out) throws IOException {
        boolean demo = args[0].equals("demo");
        boolean projectRender = args[0].equals("render-project");
        int positional = demo ? 2 : 3;
        if (args.length < positional) {
            throw new IllegalArgumentException("wrong number of arguments for " + args[0]);
        }
        int presentations = 120;
        int previousPresentations = 0;
        Path previousPath = null;
        Double start = null, end = null;
        List<String> solo = List.of(), mute = List.of();
        var options = new HashSet<String>();
        for (int at = positional; at < args.length;) {
            String option = args[at++];
            if (!options.add(option)) throw new IllegalArgumentException("duplicate option " + option);
            if (option.equals("--force")) {
                if (!projectRender) throw new IllegalArgumentException("--force requires render-project");
                continue;
            }
            if (at == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[at++];
            switch (option) {
                case "--frames" -> presentations = parsePresentations(option, value);
                case "--solo", "--mute", "--start", "--end" -> {
                    if (!projectRender) throw new IllegalArgumentException(option + " requires render-project");
                    switch (option) {
                        case "--solo" -> solo = ReconstructionView.csv(value);
                        case "--mute" -> mute = ReconstructionView.csv(value);
                        case "--start" -> start = Double.parseDouble(value);
                        case "--end" -> end = Double.parseDouble(value);
                        default -> throw new AssertionError(option);
                    }
                }
                case "--previous-project" -> {
                    if (!projectRender) throw new IllegalArgumentException(option + " requires render-project");
                    previousPath = Path.of(value);
                }
                case "--previous-frames" -> {
                    if (!projectRender) throw new IllegalArgumentException(option + " requires render-project");
                    previousPresentations = parsePresentations(option, value);
                    S3kRenderer.validatePreviousPresentations(previousPresentations);
                }
                default -> throw new IllegalArgumentException("unknown option " + option);
            }
        }
        if ((previousPath == null) != (previousPresentations == 0)) {
            throw new IllegalArgumentException("--previous-project and --previous-frames must be used together");
        }
        S3kRenderer.validatePresentations(presentations);
        Path output = Path.of(args[demo ? 1 : 2]);
        int projectEnd = presentations;
        CompiledProject song;
        if (demo) {
            song = new CompiledProject(S3kDemo.song(), List.of());
        } else {
            Path input = Path.of(args[1]);
            requireDifferentFiles(input, output);
            if (projectRender) {
                var project = ProjectFiles.load(input).project();
                song = ProjectCompiler.compileWithAssets(project);
                if (start != null || end != null) projectEnd = ProjectRenderTiming.presentationCount(project);
                if (!options.contains("--frames")) {
                    if (song.nativeProgram() != null) {
                        presentations = ProjectRenderTiming.presentationCount(project);
                    } else if (ProjectCompiler.dacEndTick(project) > 0) {
                        presentations = Math.max(presentations, ProjectRenderTiming.presentationCount(project));
                    }
                }
            } else {
                try (var stream = Files.newInputStream(input)) {
                    song = new CompiledProject(stream.readNBytes(S3kBinary.MAX_BYTES + 1), List.of());
                }
            }
        }
        boolean bounded = start != null || end != null;
        int limit = options.contains("--frames") || !bounded ? presentations : projectEnd;
        var range = bounded ? WaveFile.Range.seconds(start == null ? 0 : start, end == null ? limit / 60.0 : end)
                : new WaveFile.Range(0, presentations);
        if (options.contains("--frames") && range.end() > limit)
            throw new IllegalArgumentException("Range exceeds --frames");
        var mask = ReconstructionView.laneMask(solo, mute);
        try (var target = new ExportTarget(output, options.contains("--force"))) {
            if (previousPath == null) {
                if (bounded || !mask.equals(S3kRenderer.ChannelMask.NONE)) WaveFile.renderRange(song, target.path(), range, mask);
                else WaveFile.render(song, target.path(), presentations);
            } else {
                requireDifferentFiles(previousPath, output);
                var previous = ProjectCompiler.compileWithAssets(ProjectFiles.load(previousPath).project());
                WaveFile.renderWithPreviousMusic(previous, previousPresentations, song, target.path(), range, mask);
            }
            target.publish();
        }
        out.println("Rendered " + range.count() + " presentations to " + output
                + " (48 kHz stereo, pinned OpenGGF S3K accurate core)");
    }

    private static int parsePresentations(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(option + " must be an integer from 1 to 36000");
        }
    }

    private static int parseMusicId(String value) {
        try {
            return value.regionMatches(true, 0, "0x", 0, 2)
                    ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Music ID must be decimal or hexadecimal with a 0x prefix");
        }
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

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo OUTPUT.wav [--frames N]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render INPUT.bin OUTPUT.wav [--frames N]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo-project OUTPUT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar list-rom-songs ROM");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar import-rom-song ROM MUSIC_ID OUTPUT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar copy-arrangement PROJECT.json OUTPUT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar validate-project PROJECT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar group create PROJECT.json GROUP_ID NAME PLACEMENT_ID... [--in GROUP_ID]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar group place PROJECT.json GROUP_ID PLACEMENT_ID START LENGTH [--in GROUP_ID]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar group ungroup PROJECT.json PLACEMENT_ID [--in GROUP_ID]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar inspect-project PROJECT.json");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-project PROJECT.json OUTPUT.bin");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-package PROJECT.json OUTPUT.smps.zip");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project PROJECT.json OUTPUT.wav [--frames N] [--force]");
        out.println("           [--solo LANES] [--mute LANES] [--start S] [--end S]");
        out.println("           [--previous-project PROJECT.json --previous-frames N]");
        out.println("       render-compare WORK_DIR --start S --end S [--time source|project] [--reference WAV] [--render WAV] [--force] OUT.wav|OUT.mp3");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar catalog --help");
        ReconstructionCli.usage(out);
        out.println("       mcp  (stdio Model Context Protocol server)");
        ReviewCli.usage(out);
        BenchCli.usage(out);
        out.println("Previous-project warm-up sets the state restored by a cue. WAV starts at the cue.");
        out.println("N: 1..36000 NTSC presentations, default 120; native projects use previewFrames and arranged DAC projects include their sample tail.");
        out.println("Outputs must not already exist. Projects: strict JSON versions 1–5 with explicit native-bank dependencies.");
        out.println("Binary input: finite linear S3K data, local FM voices; envelope/DAC dependencies require render-project.");
    }
}
