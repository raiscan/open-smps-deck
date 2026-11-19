package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CatalogueCli {
    private static final int VERSION = InstrumentCatalogue.FORMAT_VERSION;
    private static final String PROFILE = "s3k-ntsc-v1";

    private CatalogueCli() {}

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length == 1 || (args.length == 2 && args[1].equals("--help"))) {
            usage(out);
            return;
        }
        try {
            switch (args[1]) {
                case "scan" -> scan(args, out);
                case "search" -> search(args, out);
                case "show" -> show(args, out);
                case "match" -> match(args, out);
                case "match-effects" -> matchEffects(args, out);
                case "audition" -> audition(args, out);
                case "apply" -> apply(args, out);
                default -> throw new IllegalArgumentException("Unknown catalogue command " + args[1]);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Catalogue scan interrupted");
        }
    }

    static void usage(PrintStream out) {
        out.println("Catalogue commands");
        out.println("  catalog scan OUTPUT.json [--rom ROM] [--rips DIRECTORY]");
        out.println("  catalog search INDEX.json [--query TEXT] [--kind KIND] [--driver S3K]");
        out.println("          [--role ROLE] [--source SOURCE] [--variant ID] [--offset N] [--limit N]");
        out.println("  catalog show INDEX.json ID");
        out.println("  catalog match INDEX.json REFERENCE.wav [--kind KIND] [--midi N] [--gate-ticks N]");
        out.println("          [--start SECONDS] [--duration SECONDS] [--role ROLE] [--source SOURCE] [--limit N]");
        out.println("  catalog match-effects PROJECT.json REFERENCE.wav OUTPUT.json --lead ID --double ID");
        out.println("  catalog audition INDEX.json ID OUTPUT.wav [--frames N] [--midi N] [--gate-ticks N]");
        out.println("  catalog apply INDEX.json ID PROJECT.json OUTPUT.json");
    }

    private static void scan(String[] args, PrintStream out) throws IOException, InterruptedException {
        var options = options(args, 3, Set.of("--rom", "--rips"));
        if (options.isEmpty()) throw new IllegalArgumentException("Supply --rom or --rips to scan");
        var builder = new InstrumentCatalogue.Builder();
        if (options.containsKey("--rom")) CatalogueSources.scanRom(Path.of(options.get("--rom")), builder);
        if (options.containsKey("--rips")) CatalogueRips.scan(Path.of(options.get("--rips")), builder);
        var catalogue = builder.build();
        catalogue.save(Path.of(args[2]));
        var counts = new EnumMap<Kind, Long>(Kind.class);
        for (var asset : catalogue.assets()) counts.merge(asset.kind(), 1L, Long::sum);
        print(out, new Scan(VERSION, PROFILE, catalogue.indexedSongs(), counts, catalogue.variants().size(),
                catalogue.issues()));
    }

    private static void search(String[] args, PrintStream out) throws IOException {
        var options = options(args, 3, Set.of("--query", "--kind", "--driver", "--role", "--source",
                "--variant", "--offset", "--limit"));
        Kind kind = options.containsKey("--kind") ? parseKind(options.get("--kind")) : null;
        var query = new InstrumentCatalogue.Query(options.get("--query"), kind, options.get("--driver"),
                options.get("--role"), options.get("--source"), options.get("--variant"),
                integer(options, "--offset", 0), integer(options, "--limit", 25));
        var page = InstrumentCatalogue.open(Path.of(args[2])).search(query);
        var summaries = page.items().stream().map(match -> {
            var variant = match.variant();
            return new Summary(match.asset().id(), variant.id(), match.asset().kind(), variant.name(), "S3K",
                    variant.recipe().rate(), variant.recipe().pan(), variant.roles(), variant.occurrences().size());
        }).toList();
        print(out, new Results(VERSION, page.total(), page.offset(), summaries));
    }

    private static void show(String[] args, PrintStream out) throws IOException {
        options(args, 4, Set.of());
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        String id = args[3];
        var exact = catalogue.variants().stream().filter(variant -> variant.id().equals(id)).findFirst();
        var asset = catalogue.asset(exact.map(InstrumentCatalogue.Variant::assetId).orElse(id));
        var variants = exact.map(List::of).orElseGet(() -> catalogue.variants().stream()
                .filter(item -> item.assetId().equals(id)).toList());
        print(out, new Details(VERSION, PROFILE, asset, variants));
    }

    private static void match(String[] args, PrintStream out) throws IOException {
        var options = options(args, 4, Set.of("--kind", "--role", "--source", "--midi", "--gate-ticks",
                "--start", "--duration", "--limit"));
        Kind kind = options.containsKey("--kind") ? parseKind(options.get("--kind")) : null;
        Integer gate = options.containsKey("--gate-ticks") ? integer(options, "--gate-ticks", 0) : null;
        var filters = new CatalogueMatcher.Options(kind, options.get("--role"), options.get("--source"),
                integer(options, "--midi", 60), gate, integer(options, "--limit", 10));
        var reference = ReferenceAudio.read(Path.of(args[3]), options.containsKey("--start")
                ? decimal(options, "--start") : 0, options.containsKey("--duration")
                ? decimal(options, "--duration") : null);
        var index = InstrumentCatalogue.open(Path.of(args[2]));
        print(out, CatalogueMatcher.match(index, reference, filters));
    }

    private static void matchEffects(String[] args, PrintStream out) throws IOException {
        var options = options(args, 5, Set.of("--lead", "--double"));
        String lead = options.get("--lead"), doubled = options.get("--double");
        if (lead == null || doubled == null)
            throw new IllegalArgumentException("match-effects requires --lead and --double sequence IDs");
        Path output = Path.of(args[4]);
        if (java.nio.file.Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(output.toString());
        var source = ProjectFiles.load(Path.of(args[2])).project();
        var result = LeadEffectMatcher.match(source, Path.of(args[3]), lead, doubled);
        ProjectFiles.save(output, result.project(), null);
        print(out, result.report());
    }

    private static double decimal(Map<String, String> options, String flag) {
        try {
            double value = Double.parseDouble(options.get(flag));
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // Report the option name, rather than the parser implementation.
        }
        throw new IllegalArgumentException(flag + " must be finite seconds");
    }

    private static void audition(String[] args, PrintStream out) throws IOException {
        var options = options(args, 5, Set.of("--frames", "--midi", "--gate-ticks"));
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        boolean matchProbe = options.containsKey("--midi") || options.containsKey("--gate-ticks");
        var project = matchProbe
                ? CataloguePlayback.matchProbe(catalogue, args[3], integer(options, "--midi", 60),
                    integer(options, "--gate-ticks", 60))
                : CataloguePlayback.audition(catalogue, args[3]);
        int frames = integer(options, "--frames", ProjectRenderTiming.presentationCount(project));
        WaveFile.render(ProjectCompiler.compileWithAssets(project), Path.of(args[4]), frames);
        print(out, new Output(VERSION, PROFILE, catalogue.match(args[3]).variant().id(), frames));
    }

    private static void apply(String[] args, PrintStream out) throws IOException {
        options(args, 6, Set.of());
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        var source = ProjectFiles.load(Path.of(args[4])).project();
        var result = CataloguePlayback.apply(catalogue, args[3], source);
        ProjectFiles.save(Path.of(args[5]), result, null);
        print(out, new Output(VERSION, PROFILE, catalogue.match(args[3]).variant().id(), null));
    }

    private static Map<String, String> options(String[] args, int start, Set<String> accepted) {
        if (args.length < start) throw new IllegalArgumentException("Missing arguments for catalog " + args[1]);
        var options = new HashMap<String, String>();
        boolean json = false;
        for (int at = start; at < args.length; at++) {
            String option = args[at];
            if (option.equals("--json") && !json) {
                json = true;
                continue;
            }
            if (!accepted.contains(option) || at + 1 >= args.length)
                throw new IllegalArgumentException("Invalid catalogue option " + option);
            if (options.putIfAbsent(option, args[++at]) != null)
                throw new IllegalArgumentException("Duplicate option " + option);
        }
        return options;
    }

    private static int integer(Map<String, String> options, String name, int fallback) {
        try {
            return options.containsKey(name) ? Integer.parseInt(options.get(name)) : fallback;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static Kind parseKind(String value) {
        try {
            return Kind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown asset kind " + value);
        }
    }

    private static void print(PrintStream out, Object value) { out.println(InstrumentCatalogue.json(value)); }

    private record Scan(int formatVersion, String auditionProfile, int indexedSongs, Map<Kind, Long> assets,
                        int variants, List<InstrumentCatalogue.Issue> issues) {}
    private record Results(int formatVersion, int total, int offset, List<Summary> items) {}
    private record Summary(String assetId, String variantId, Kind kind, String name, String driver, Integer rate,
                           Integer pan, List<String> roles, int occurrenceCount) {}
    private record Details(int formatVersion, String auditionProfile, InstrumentCatalogue.Asset asset,
                           List<InstrumentCatalogue.Variant> variants) {}
    private record Output(int formatVersion, String auditionProfile, String variantId, Integer frames) {}
}
