package com.opensmpsdeck.headless.bench;

import com.google.gson.JsonElement;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.opensmpsdeck.project.CompiledProject;

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
        out.println("       bench build-corpus ROM INDEX.json OUT_DIR [--songs IDS | --quick] [--seconds N] [--profile suno-like]");
        out.println("       bench run MANIFEST.json INDEX.json OUT_DIR [--quick] [--holdout] [--audio-refine] [--jobs N]");
        out.println("           [--gold GOLD.json [--gold-only]]");
        out.println("       bench compare OLD.json NEW.json [--tolerance N]");
    }

    public static int run(String[] args, PrintStream out, Hooks hooks) throws IOException {
        if (args.length == 2 && args[1].equals("--help")) {
            usage(out);
            return 0;
        }
        if (args.length < 2) throw new IllegalArgumentException("Expected bench build-corpus, run or compare");
        return switch (args[1]) {
            case "build-corpus" -> build(args, out, hooks);
            case "run" -> runCases(args, out, hooks);
            case "compare" -> compare(args, out);
            default -> throw new IllegalArgumentException("Unknown bench command: " + args[1]);
        };
    }

    private static int build(String[] args, PrintStream out, Hooks hooks) throws IOException {
        require(args, 5);
        List<Integer> songs = BenchCorpus.DEFAULT;
        int frames = 30 * 60;
        boolean sunoLike = false;
        var seen = new HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String flag = unique(seen, args[i]);
            switch (flag) {
                case "--quick" -> songs = BenchCorpus.QUICK;
                case "--profile" -> {
                    if (!value(args, ++i, flag).equals("suno-like"))
                        throw new IllegalArgumentException("The separate corpus profile is suno-like");
                    sunoLike = true;
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
        BenchCorpus.build(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]).toAbsolutePath().normalize(),
                songs, frames, out, hooks.sequencer(), sunoLike);
        return 0;
    }

    private static int runCases(String[] args, PrintStream out, Hooks hooks) throws IOException {
        require(args, 5);
        Path gold = null;
        Integer jobs = null;
        var seen = new HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String flag = unique(seen, args[i]);
            switch (flag) {
                case "--quick", "--holdout", "--audio-refine", "--gold-only" -> { }
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
        return BenchRun.run(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
                new BenchRun.Options(seen.contains("--quick"), seen.contains("--holdout"),
                        seen.contains("--audio-refine"), jobs, gold, seen.contains("--gold-only")), out, hooks);
    }

    private static int compare(String[] args, PrintStream out) throws IOException {
        require(args, 4);
        double tolerance = .01;
        if (args.length != 4) {
            if (args.length != 6 || !args[4].equals("--tolerance"))
                throw new IllegalArgumentException("Expected bench compare OLD.json NEW.json [--tolerance N]");
            tolerance = Double.parseDouble(args[5]);
        }
        return BenchScorecard.print(compareCards(Path.of(args[2]), Path.of(args[3]), tolerance), out);
    }

    public static com.google.gson.JsonObject compareJson(Path old, Path current, double tolerance) throws IOException {
        return new com.google.gson.GsonBuilder().serializeNulls().create()
                .toJsonTree(compareCards(old, current, tolerance)).getAsJsonObject();
    }

    private static BenchScorecard.Comparison compareCards(Path old, Path current, double tolerance) throws IOException {
        if (!Double.isFinite(tolerance) || tolerance < 0)
            throw new IllegalArgumentException("--tolerance must be finite and nonnegative");
        return BenchScorecard.compare(BenchFiles.read(old, BenchScorecard.Card.class),
                BenchFiles.read(current, BenchScorecard.Card.class), tolerance);
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
