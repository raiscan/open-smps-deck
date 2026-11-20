package com.opensmpsdeck.headless.bench;

import com.google.gson.JsonElement;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.headless.LeadGap;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class BenchCli {
    @FunctionalInterface
    public interface Reconstruction {
        void run(String[] args, PrintStream out) throws IOException;
    }

    @FunctionalInterface
    public interface SequencerFactory {
        SmpsSequencer create(CompiledProject project, MusicRestoreSink restore, Sonic3kCoordFlagHandler handler);
    }

    @FunctionalInterface
    public interface MidiReader {
        JsonElement read(Path path) throws IOException;
    }

    public record Hooks(Reconstruction reconstruction, SequencerFactory sequencer, MidiReader midi) {}

    private BenchCli() {}

    public static void usage(PrintStream out) {
        out.println("       bench build-corpus ROM INDEX.json OUT_DIR [--songs IDS | --quick] [--seconds N] [--stems] [--profile suno-like [--drift N]]");
        out.println("       bench run MANIFEST.json INDEX.json OUT_DIR [--quick] [--holdout] [--audio-refine] [--jobs N]");
        out.println("           [--gold GOLD.json [--gold-only]] [--role-plan oracle|oracle-section] [--steady-tempo off|auto]");
        out.println("       bench lead-gap WORK_DIR (--stem LEAD.wav | --gold GOLD.json)");
        out.println("       bench compare OLD.json NEW.json [--tolerance N] [--alternate-midi OLD_MANIFEST NEW_MANIFEST]");
    }

    public static int run(String[] args, PrintStream out, Hooks hooks) throws IOException {
        if (args.length == 2 && args[1].equals("--help")) {
            usage(out);
            return 0;
        }
        if (args.length < 2) throw new IllegalArgumentException("Expected bench build-corpus, run, lead-gap or compare");
        return switch (args[1]) {
            case "build-corpus" -> build(args, out, hooks);
            case "run" -> runCases(args, out, hooks);
            case "lead-gap" -> leadGap(args, out, hooks);
            case "compare" -> compare(args, out);
            default -> throw new IllegalArgumentException("Unknown bench command: " + args[1]);
        };
    }

    private static int build(String[] args, PrintStream out, Hooks hooks) throws IOException {
        require(args, 5);
        List<Integer> songs = BenchCorpus.DEFAULT;
        int frames = 30 * 60;
        boolean sunoLike = false;
        boolean stems = false;
        double drift = 0;
        var seen = new HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String flag = unique(seen, args[i]);
            switch (flag) {
                case "--quick" -> songs = BenchCorpus.QUICK;
                case "--stems" -> stems = true;
                case "--profile" -> {
                    if (!value(args, ++i, flag).equals("suno-like"))
                        throw new IllegalArgumentException("The separate corpus profile is suno-like");
                    sunoLike = true;
                }
                case "--drift" -> {
                    drift = Double.parseDouble(value(args, ++i, flag));
                    if (!(drift > 0 && drift <= .18))
                        throw new IllegalArgumentException("--drift must be greater than 0 and at most 0.18");
                }
                case "--seconds" -> {
                    double seconds = Double.parseDouble(value(args, ++i, flag));
                    if (!(seconds >= 1.0 / 60 && seconds <= 600))
                        throw new IllegalArgumentException("Corpus duration must be one presentation to 600 seconds");
                    frames = (int) Math.round(seconds * 60);
                }
                case "--songs" -> {
                    songs = Arrays.stream(value(args, ++i, flag).split(",", -1)).map(String::strip)
                            .map(BenchCli::songId).toList();
                    if (new HashSet<>(songs).size() != songs.size())
                        throw new IllegalArgumentException("Duplicate music ID");
                }
                default -> throw new IllegalArgumentException("Unknown build-corpus option: " + flag);
            }
        }
        if (seen.contains("--quick") && seen.contains("--songs"))
            throw new IllegalArgumentException("Choose either --quick or --songs");
        if (sunoLike && frames < 2)
            throw new IllegalArgumentException("A shifted corpus needs at least two presentations");
        if (drift != 0 && !sunoLike) throw new IllegalArgumentException("--drift requires --profile suno-like");
        BenchCorpus.build(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]).toAbsolutePath().normalize(),
                songs, frames, out, hooks.sequencer(), sunoLike, drift, stems);
        return 0;
    }

    private static int runCases(String[] args, PrintStream out, Hooks hooks) throws IOException {
        require(args, 5);
        Path gold = null;
        Integer jobs = null;
        String steadyTempo = "auto";
        BenchRolePlan.Mode rolePlan = null;
        var seen = new HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String flag = unique(seen, args[i]);
            switch (flag) {
                case "--quick", "--holdout", "--audio-refine", "--gold-only" -> { }
                case "--steady-tempo" -> {
                    steadyTempo = value(args, ++i, flag);
                    if (!List.of("off", "auto").contains(steadyTempo))
                        throw new IllegalArgumentException("bench run --steady-tempo expects off or auto");
                }
                case "--role-plan" -> rolePlan = switch (value(args, ++i, flag)) {
                    case "oracle" -> BenchRolePlan.Mode.ORACLE;
                    case "oracle-section" -> BenchRolePlan.Mode.ORACLE_SECTION;
                    default -> throw new IllegalArgumentException("--role-plan expects oracle or oracle-section");
                };
                case "--gold" -> gold = Path.of(value(args, ++i, flag));
                case "--jobs" -> {
                    jobs = Integer.parseInt(value(args, ++i, flag));
                    if (jobs < 1 || jobs > 32) throw new IllegalArgumentException("--jobs must be in 1..32");
                }
                default -> throw new IllegalArgumentException("Unknown bench run option: " + flag);
            }
        }
        if (jobs == null) {
            int limit = seen.contains("--quick") ? 16 : 8;
            jobs = Math.min(limit, Runtime.getRuntime().availableProcessors());
        }
        if (rolePlan != null && seen.contains("--gold-only"))
            throw new IllegalArgumentException("--role-plan requires native corpus cases");
        return BenchRun.run(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
                new BenchRun.Options(seen.contains("--quick"), seen.contains("--holdout"),
                        seen.contains("--audio-refine"), jobs, gold, seen.contains("--gold-only"), rolePlan, steadyTempo), out, hooks);
    }

    private static int leadGap(String[] args, PrintStream out, Hooks hooks) throws IOException {
        if (args.length != 5 || !List.of("--gold", "--stem").contains(args[3]))
            throw new IllegalArgumentException("Expected bench lead-gap WORK_DIR (--stem LEAD.wav | --gold GOLD.json)");
        Path work = Path.of(args[2]);
        var report = BenchFiles.read(work.resolve("report.json"), BenchReconstruction.Report.class);
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var notes = BenchTimeline.read(ProjectCompiler.compileWithAssets(project), report.presentations(), hooks.sequencer());
        var gates = notes.channels().stream().filter(channel -> channel.kind().equals("FM1"))
                .flatMap(channel -> channel.notes().stream()).map(note -> new LeadGap.Interval(note.start(), note.end()))
                .sorted(java.util.Comparator.comparingDouble(LeadGap.Interval::start)).toList();
        Path reference = Path.of(args[4]);
        var measurement = args[3].equals("--gold")
                ? BenchGold.leadGap(work, BenchGold.read(reference), gates)
                : LeadGap.measure(work, gates, List.of(), List.of(), reference);
        out.println(BenchFiles.JSON.toJson(measurement));
        return 0;
    }

    private static int compare(String[] args, PrintStream out) throws IOException {
        require(args, 4);
        double tolerance = .01;
        Path oldManifest = null, newManifest = null;
        var seen = new HashSet<String>();
        for (int i = 4; i < args.length; i++) {
            String flag = unique(seen, args[i]);
            switch (flag) {
                case "--tolerance" -> tolerance = Double.parseDouble(value(args, ++i, flag));
                case "--alternate-midi" -> {
                    oldManifest = Path.of(value(args, ++i, flag));
                    newManifest = Path.of(value(args, ++i, flag));
                }
                default -> throw new IllegalArgumentException("Unknown compare option: " + flag);
            }
        }
        Path old = Path.of(args[2]), current = Path.of(args[3]);
        if (oldManifest == null) return BenchScorecard.print(compareCards(old, current, tolerance), out);
        validateTolerance(tolerance);
        var before = BenchFiles.read(old, BenchScorecard.Card.class);
        var after = BenchFiles.read(current, BenchScorecard.Card.class);
        if (!before.manifestSha256().equals(BenchFiles.hash(oldManifest))
                || !after.manifestSha256().equals(BenchFiles.hash(newManifest))
                || !before.catalogueSha256().equals(after.catalogueSha256())
                || before.audioRefine() != after.audioRefine()
                || !withoutMidi(oldManifest).equals(withoutMidi(newManifest))) {
            throw new IllegalArgumentException("Alternate MIDI comparison requires verified manifests differing only in MIDI");
        }
        return BenchScorecard.print(BenchScorecard.compare(before, after, tolerance, true), out);
    }

    private static com.google.gson.JsonObject withoutMidi(Path path) throws IOException {
        var manifest = BenchFiles.read(path, com.google.gson.JsonObject.class);
        for (var row : manifest.getAsJsonArray("cases")) row.getAsJsonObject().remove("midi");
        return manifest;
    }

    public static com.google.gson.JsonObject compareJson(Path old, Path current, double tolerance) throws IOException {
        return new com.google.gson.GsonBuilder().serializeNulls().create()
                .toJsonTree(compareCards(old, current, tolerance)).getAsJsonObject();
    }

    private static BenchScorecard.Comparison compareCards(Path old, Path current, double tolerance) throws IOException {
        validateTolerance(tolerance);
        return BenchScorecard.compare(BenchFiles.read(old, BenchScorecard.Card.class),
                BenchFiles.read(current, BenchScorecard.Card.class), tolerance);
    }

    private static void validateTolerance(double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0)
            throw new IllegalArgumentException("--tolerance must be finite and nonnegative");
    }

    private static int songId(String text) {
        String id = text.toLowerCase(Locale.ROOT);
        int value = id.startsWith("0x") ? Integer.parseInt(id.substring(2), 16) : Integer.parseInt(id);
        if (value < 1 || value > 0x33) throw new IllegalArgumentException("Music ID must be in 0x01–0x33");
        return value;
    }

    private static void require(String[] args, int count) {
        if (args.length < count) throw new IllegalArgumentException("Missing arguments to bench " + args[1]);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[index];
    }

    private static String unique(HashSet<String> seen, String flag) {
        if (!seen.add(flag)) throw new IllegalArgumentException("Duplicate option " + flag);
        return flag;
    }
}
