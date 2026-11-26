package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.headless.rom.VerifiedRom;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.TargetGame;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CatalogueCli {
    private static final int VERSION = InstrumentCatalogue.FORMAT_VERSION;

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
                case "dac-classes" -> {
                    options(args, 3, Set.of());
                    print(out, new DacAcoustics(InstrumentCatalogue.open(Path.of(args[2]))).report());
                }
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
        out.println("  catalog scan OUTPUT.json [--game s1|s2|s3k] [--rom ROM ...] [--rips DIRECTORY]");
        out.println("  catalog search INDEX.json [--query TEXT] [--kind KIND] [--target s1|s2|s3k]");
        out.println("          [--role ROLE] [--source SOURCE] [--variant ID] [--offset N] [--limit N]");
        out.println("          [--min-centroid-hz HZ] [--max-centroid-hz HZ] [--max-decay-db DB]");
        out.println("  catalog show INDEX.json ID [--brief]");
        out.println("  catalog dac-classes INDEX.json");
        out.println("  catalog match INDEX.json REFERENCE.wav [--target s1|s2|s3k] [--kind KIND] [--midi N] [--gate-ticks N]");
        out.println("          [--start SECONDS] [--duration SECONDS] [--role ROLE] [--source SOURCE] [--limit N]");
        out.println("  catalog match-effects PROJECT.json REFERENCE.wav OUTPUT.json --lead ID --double ID");
        out.println("  catalog audition INDEX.json ID OUTPUT.wav [--target s1|s2|s3k] [--frames N] [--midi N] [--gate-ticks N]");
        out.println("  catalog apply INDEX.json ID PROJECT.json OUTPUT.json");
    }

    private static void scan(String[] args, PrintStream out) throws IOException, InterruptedException {
        var roms = new ArrayList<Path>();
        var options = options(args, 3, Set.of("--rom", "--rips", "--game"), roms);
        var builder = new InstrumentCatalogue.Builder();
        RomGame game = options.containsKey("--game") ? RomGame.parse(options.get("--game")) : null;
        if (!roms.isEmpty()) {
            for (Path path : roms)
                CatalogueSources.scanRom(game == null ? VerifiedRom.read(path) : VerifiedRom.read(game, path), builder);
        } else if (game != null || !options.containsKey("--rips")) {
            CatalogueSources.scanRom(RomLibrary.userLibrary().resolve(game == null ? RomGame.S3K : game, null), builder);
        }
        if (options.containsKey("--rips")) CatalogueRips.scan(Path.of(options.get("--rips")), builder);
        var catalogue = builder.build();
        catalogue.save(Path.of(args[2]));
        var counts = new EnumMap<Kind, Long>(Kind.class);
        for (var asset : catalogue.assets()) counts.merge(asset.kind(), 1L, Long::sum);
        print(out, new Scan(VERSION, profile(catalogue.variants()), catalogue.indexedSongs(), counts, catalogue.variants().size(),
                catalogue.issues()));
    }

    private static void search(String[] args, PrintStream out) throws IOException {
        var options = options(args, 3, Set.of("--query", "--kind", "--driver", "--target", "--role", "--source",
                "--variant", "--offset", "--limit", "--min-centroid-hz", "--max-centroid-hz", "--max-decay-db"));
        Kind kind = options.containsKey("--kind") ? parseKind(options.get("--kind")) : null;
        String driver = options.containsKey("--target") ? target(options).id() : options.get("--driver");
        if (options.containsKey("--target") && options.containsKey("--driver"))
            throw new IllegalArgumentException("Choose --target or --driver");
        var query = new InstrumentCatalogue.Query(options.get("--query"), kind, driver,
                options.get("--role"), options.get("--source"), options.get("--variant"),
                integer(options, "--offset", 0), integer(options, "--limit", 25));
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        var measured = new java.util.HashMap<String, CatalogueSustain.Measurement>();
        var filter = new CatalogueSustain.Filter(decimalOption(options, "--min-centroid-hz"),
                decimalOption(options, "--max-centroid-hz"), decimalOption(options, "--max-decay-db"));
        InstrumentCatalogue.Page page;
        if (filter.minCentroidHz() == null && filter.maxCentroidHz() == null && filter.maxDecayDb() == null) {
            page = catalogue.search(query);
        } else {
            if (kind != null && kind != Kind.FM_VOICE)
                throw new IllegalArgumentException("Centroid and held decay filters apply to FM voices");
            var eligible = new ArrayList<InstrumentCatalogue.Match>();
            var probes = new java.util.HashMap<List<String>, CatalogueSustain.Measurement>();
            for (var match : catalogue.matching(query)) {
                if (match.asset().kind() != Kind.FM_VOICE) continue;
                var key = List.of(match.asset().id(), match.variant().driver());
                if (!probes.containsKey(key)) probes.put(key, CatalogueSustain.probe(catalogue, match));
                var result = probes.get(key);
                if (filter.accepts(result)) {
                    eligible.add(match);
                    measured.put(match.variant().id(), result);
                }
            }
            page = InstrumentCatalogue.page(query, eligible);
        }
        var summaries = page.items().stream().map(match -> {
            var variant = match.variant();
            var roles = new ArrayList<>(variant.roles());
            if (query.role() != null || measured.containsKey(variant.id())) {
                for (String role : InstrumentCatalogue.observedFmRoles(match))
                    if (!roles.contains(role)) roles.add(role);
            }
            return new Summary(match.asset().id(), variant.id(), match.asset().kind(), variant.name(), variant.driver(),
                    variant.recipe().rate(), variant.recipe().pan(), roles, InstrumentCatalogue.labels(match),
                    variant.occurrences().size(), measured.get(variant.id()));
        }).toList();
        print(out, new Results(VERSION, page.total(), page.offset(), summaries));
    }

    private static Double decimalOption(Map<String, String> options, String name) {
        return options.containsKey(name) ? Double.parseDouble(options.get(name)) : null;
    }

    private static void show(String[] args, PrintStream out) throws IOException {
        var options = new ArrayList<>(List.of(args));
        boolean brief = options.subList(Math.min(4, options.size()), options.size()).remove("--brief");
        options(options.toArray(String[]::new), 4, Set.of());
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        String id = args[3];
        var exact = catalogue.variants().stream().filter(variant -> variant.id().equals(id)).findFirst();
        var asset = catalogue.asset(exact.map(InstrumentCatalogue.Variant::assetId).orElse(id));
        var variants = exact.map(List::of).orElseGet(() -> catalogue.variants().stream()
                .filter(item -> item.assetId().equals(id)).toList());
        var labels = variants.stream().anyMatch(variant -> variant.target().s3kRules())
                ? InstrumentCatalogue.labels(asset) : List.<InstrumentCatalogue.Label>of();
        var details = new Details(VERSION, profile(variants), asset, labels, variants);
        if (!brief) {
            print(out, details);
            return;
        }
        var json = new Gson().toJsonTree(details).getAsJsonObject();
        for (var variant : json.getAsJsonArray("variants")) {
            var entry = variant.getAsJsonObject();
            var occurrences = entry.remove("occurrences").getAsJsonArray();
            entry.addProperty("occurrenceCount", occurrences.size());
        }
        print(out, json);
    }

    private static void match(String[] args, PrintStream out) throws IOException {
        var options = options(args, 4, Set.of("--kind", "--role", "--source", "--midi", "--gate-ticks", "--target",
                "--start", "--duration", "--limit"));
        Kind kind = options.containsKey("--kind") ? parseKind(options.get("--kind")) : null;
        Integer gate = options.containsKey("--gate-ticks") ? integer(options, "--gate-ticks", 0) : null;
        var filters = new CatalogueMatcher.Options(kind, options.get("--role"), options.get("--source"),
                integer(options, "--midi", 60), gate, integer(options, "--limit", 10));
        var reference = ReferenceAudio.read(Path.of(args[3]), options.containsKey("--start")
                ? decimal(options, "--start") : 0, options.containsKey("--duration")
                ? decimal(options, "--duration") : null);
        var index = InstrumentCatalogue.open(Path.of(args[2]));
        print(out, CatalogueMatcher.match(index, reference, filters, target(options)));
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
        var options = options(args, 5, Set.of("--frames", "--midi", "--gate-ticks", "--target"));
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        TargetGame target = options.containsKey("--target") ? target(options) : null;
        String variantId = catalogue.match(args[3], target).variant().id();
        boolean matchProbe = options.containsKey("--midi") || options.containsKey("--gate-ticks");
        var project = matchProbe
                ? CataloguePlayback.matchProbe(catalogue, variantId, integer(options, "--midi", 60),
                    integer(options, "--gate-ticks", 60), false, target)
                : CataloguePlayback.audition(catalogue, variantId, target);
        int frames = integer(options, "--frames", ProjectRenderTiming.presentationCount(project));
        WaveFile.render(project, Path.of(args[4]), frames);
        print(out, new Output(VERSION, profile(project.target()), variantId, frames));
    }

    private static void apply(String[] args, PrintStream out) throws IOException {
        options(args, 6, Set.of());
        var catalogue = InstrumentCatalogue.open(Path.of(args[2]));
        var source = ProjectFiles.load(Path.of(args[4])).project();
        var result = CataloguePlayback.apply(catalogue, args[3], source);
        ProjectFiles.save(Path.of(args[5]), result, null);
        print(out, new Output(VERSION, profile(source.target()), catalogue.match(args[3], source.target()).variant().id(), null));
    }

    private static Map<String, String> options(String[] args, int start, Set<String> accepted) {
        return options(args, start, accepted, null);
    }

    private static Map<String, String> options(String[] args, int start, Set<String> accepted, List<Path> roms) {
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
            String value = args[++at];
            if (roms != null && option.equals("--rom")) roms.add(Path.of(value));
            else if (options.putIfAbsent(option, value) != null)
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

    private static TargetGame target(Map<String, String> options) {
        return TargetGame.parse(options.getOrDefault("--target", "s3k"));
    }

    private static String profile(List<InstrumentCatalogue.Variant> variants) {
        var targets = variants.stream().map(InstrumentCatalogue.Variant::target).distinct().toList();
        return targets.size() > 1 ? null : profile(targets.isEmpty() ? TargetGame.S3K : targets.getFirst());
    }

    private static String profile(TargetGame target) {
        return target.rulebook().id() + "-ntsc-v1";
    }

    private static void print(PrintStream out, Object value) { out.println(InstrumentCatalogue.json(value)); }

    private record Scan(int formatVersion, String auditionProfile, int indexedSongs, Map<Kind, Long> assets,
                        int variants, List<InstrumentCatalogue.Issue> issues) {}
    private record Results(int formatVersion, int total, int offset, List<Summary> items) {}
    private record Summary(String assetId, String variantId, Kind kind, String name, String driver, Integer rate,
                           Integer pan, List<String> roles, List<InstrumentCatalogue.Label> labels, int occurrenceCount,
                           CatalogueSustain.Measurement heldNote) {}
    private record Details(int formatVersion, String auditionProfile, InstrumentCatalogue.Asset asset,
                           List<InstrumentCatalogue.Label> labels, List<InstrumentCatalogue.Variant> variants) {}
    private record Output(int formatVersion, String auditionProfile, String variantId, Integer frames) {}
}
