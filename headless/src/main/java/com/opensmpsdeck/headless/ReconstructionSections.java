package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Read-only effective musical form for reconstruction clients, including Studio. */
public final class ReconstructionSections {
    public static final String FILE = "sections.json";
    public static final String LOCK = ".sections.lock";
    private static final double EPSILON = 1e-6;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public enum Level { section, period, phrase }
    public enum Author { automatic, human, agent }

    /** Times and one-based bar positions are half-open. Fractional bars mark in-bar boundaries. */
    public record Section(String id, Level level, String label, Author author, String reason,
                          double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                          double startBar, double endBar, double startConfidence, double endConfidence,
                          String parentId) {
        private Section withParent(String parent) {
            return new Section(id, level, label, author, reason, sourceStart, sourceEnd,
                    projectStart, projectEnd, startBar, endBar, startConfidence, endConfidence, parent);
        }
    }

    public record Issue(String id, String detail) {}

    public record Result(List<Section> sections, List<Section> automatic, List<Issue> issues, List<String> warnings) {
        /** Resolve a stable ID or a unique literal display name. */
        public Section select(String name) {
            var exact = sections.stream().filter(section -> section.id.equals(name)).findFirst();
            if (exact.isPresent()) return exact.get();
            // Often the same automatic letter occurs at multiple levels or in multiple passages.
            var named = sections.stream().filter(section -> section.label.equalsIgnoreCase(name)).toList();
            require(!named.isEmpty(), "section", "no effective span named " + name);
            require(named.size() == 1, "section", "ambiguous name " + name + "; use one of "
                    + named.stream().map(Section::id).toList());
            return named.getFirst();
        }
    }

    record Grid(List<ReconstructionStructure.Bar> bars, List<Double> points, ReconstructionTimeMap timeline) {
        static Grid of(ReconstructionStages.Score score) {
            var timeline = ReconstructionTimeMap.of(score);
            var bars = score.usesBeatGrid() ? score.beatMap().bars(timeline.sourceDuration()) : score.bars();
            var points = new ArrayList<Double>();
            if (!bars.isEmpty()) {
                points.add(bars.getFirst().start());
                for (var bar : bars) {
                    var beatMap = score.beatMap();
                    double middle = score.usesBeatGrid()
                            ? beatMap.seconds((beatMap.index(bar.start()) + beatMap.index(bar.end())) / 2)
                            : bar.at(.5);
                    points.add(middle);
                    points.add(bar.end());
                }
            }
            return new Grid(bars, List.copyOf(points), timeline);
        }

        double bar(double seconds) {
            if (points.isEmpty()) return 1;
            int at = java.util.Collections.binarySearch(points, seconds);
            if (at >= 0) return 1 + at / 2.0;
            int high = Math.clamp(-at - 1, 1, points.size() - 1);
            double low = points.get(high - 1);
            double fraction = Math.clamp((seconds - low) / (points.get(high) - low), 0, 1);
            return 1 + (high - 1 + fraction) / 2;
        }

        Section section(String id, Level level, String label, Author author, String reason, double start,
                        double end, double startConfidence, double endConfidence) {
            return new Section(id, level, label, author, reason, start, end, timeline.project(start), timeline.project(end),
                    bar(start), bar(end), startConfidence, endConfidence, null);
        }
    }

    private record Entry(String id, Level level, String name, Author author, String reason, double sourceStart,
                         double sourceEnd, double startBar, double endBar, boolean removed) {}
    private record Journal(int version, List<Entry> entries) {}
    private record Change(String id, @Nullable Level level, @Nullable String name, Author author, String reason,
                          @Nullable Double sourceStart, @Nullable Double sourceEnd, @Nullable Boolean removed) {}
    private record Request(int version, List<Change> entries) {}

    private final Grid grid;
    private final List<Section> automatic;
    private final List<String> warnings;

    private ReconstructionSections(ReconstructionStages.Score score,
                                   ReconstructionStructure.Result structure) throws IOException {
        grid = Grid.of(score);
        var diagnostics = new ArrayList<String>();
        automatic = withParents(MusicalSectionProposal.propose(score, grid, structure, diagnostics));
        warnings = List.copyOf(diagnostics);
    }

    private static ReconstructionStructure.Result savedStructure(Path work) throws IOException {
        var file = work.resolve("structure.json");
        return Files.isRegularFile(file) ? ReconstructionArtifacts.read(file, ReconstructionStructure.Result.class) : null;
    }

    /** Load the current proposal and effective hierarchy without writing anything to the work directory. */
    public static Result load(Path work) throws IOException {
        return load(work, ReconstructionStages.readScore(work.resolve("score.json")));
    }

    static Result load(Path work, ReconstructionStages.Score score) throws IOException {
        return load(work, score, savedStructure(work));
    }

    static Result load(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure) throws IOException {
        return new ReconstructionSections(score, structure).effective(read(work).entries);
    }

    /** Resolve CLI/MCP bounds in the caller's selected clock. */
    public static double[] bounds(Path work, String name, Double start, Double end, String time) throws IOException {
        var clock = ReconstructionTimeMap.TimeBase.valueOf(time);
        if (name == null) {
            require(start != null && end != null, "range", "expected start and end or a section");
            return new double[]{start, end};
        }
        require(start == null && end == null, "section", "choose a section or explicit start and end");
        var span = load(work).select(name);
        return clock == ReconstructionTimeMap.TimeBase.project
                ? new double[]{span.projectStart, span.projectEnd} : new double[]{span.sourceStart, span.sourceEnd};
    }

    private static Journal read(Path work) throws IOException {
        var file = work.resolve(FILE);
        if (!Files.exists(file, NOFOLLOW_LINKS)) return new Journal(ReconstructionStages.VERSION, List.of());
        var journal = ReconstructionArtifacts.read(file, Journal.class);
        var ids = new java.util.HashSet<String>();
        for (var entry : journal.entries) {
            validateIdentity(entry.id, entry.author, entry.reason);
            require(ids.add(entry.id), FILE, "duplicate ID " + entry.id);
            require(!entry.name.isBlank(), FILE, "blank name for " + entry.id);
            require(entry.sourceStart >= 0 && entry.sourceEnd > entry.sourceStart
                    && entry.startBar >= 1 && entry.endBar >= entry.startBar, FILE, "invalid anchors for " + entry.id);
        }
        return journal;
    }

    private static boolean intersects(double start, double end, Entry entry) {
        return entry.sourceStart < end && start < entry.sourceEnd;
    }

    private static List<Section> withParents(List<Section> sections) {
        return sections.stream().map(child -> {
            if (child.level == Level.section) return child;
            for (var level : child.level == Level.phrase ? List.of(Level.period, Level.section) : List.of(Level.section)) {
                for (var parent : sections)
                    if (parent.level == level && parent.sourceStart <= child.sourceStart && child.sourceEnd <= parent.sourceEnd)
                        return child.withParent(parent.id);
            }
            return child;
        }).toList();
    }

    /** Report moved grid anchors and entries outside the current duration without analyzing audio. */
    public static List<Issue> anchorIssues(Path work) throws IOException {
        if (!Files.exists(work.resolve(FILE), NOFOLLOW_LINKS)) return List.of();
        return anchorIssues(work, ReconstructionStages.readScore(work.resolve("score.json")));
    }

    static List<Issue> anchorIssues(Path work, ReconstructionStages.Score score) throws IOException {
        return anchorIssues(Grid.of(score), read(work).entries);
    }

    private static List<Issue> anchorIssues(Grid grid, List<Entry> entries) {
        var issues = new ArrayList<Issue>();
        for (var entry : entries) {
            if (entry.sourceEnd > grid.timeline.sourceDuration())
                issues.add(new Issue(entry.id, "Source span is outside the current run"));
            else if (Math.abs(grid.bar(entry.sourceStart) - entry.startBar) > .25
                    || Math.abs(grid.bar(entry.sourceEnd) - entry.endBar) > .25)
                issues.add(new Issue(entry.id, "Bar anchor shifted by more than a quarter bar; retained source seconds"));
        }
        return List.copyOf(issues);
    }

    private Result effective(List<Entry> entries) {
        var issues = anchorIssues(grid, entries);
        var authored = entries.stream().filter(entry -> entry.sourceEnd <= grid.timeline.sourceDuration()).toList();
        if (authored.isEmpty()) return new Result(automatic, automatic, issues, warnings);
        validateOverlap(authored);
        var effective = new ArrayList<Section>();
        var partitions = new EnumMap<Level, List<Section>>(Level.class);
        var changedIds = authored.stream().map(Entry::id).collect(java.util.stream.Collectors.toSet());
        for (var level : Level.values()) {
            var changes = authored.stream().filter(entry -> entry.level == level).toList();
            var proposals = automatic.stream().filter(span -> span.level == level).toList();
            var cuts = new TreeSet<Double>();
            var parentCuts = new TreeSet<Double>();
            for (var spans : partitions.values()) for (var span : spans) {
                parentCuts.add(span.sourceStart);
                parentCuts.add(span.sourceEnd);
            }
            for (var span : proposals) {
                cuts.add(span.sourceStart);
                cuts.add(span.sourceEnd);
            }
            cuts.addAll(parentCuts);
            // An authored child may cross a proposed boundary. Preserve the child and omit that automatic cut.
            cuts.removeIf(at -> authored.stream().anyMatch(entry -> !entry.removed
                    && entry.level.ordinal() >= level.ordinal() && entry.sourceStart < at && at < entry.sourceEnd));
            for (var entry : changes) {
                for (double edge : new double[]{entry.sourceStart, entry.sourceEnd}) {
                    if (changes.stream().noneMatch(e -> !e.removed && e.sourceStart < edge && edge < e.sourceEnd))
                        cuts.add(edge);
                }
            }
            var spans = new ArrayList<Section>();
            Double from = null;
            for (double to : cuts) {
                if (from != null && to > from + EPSILON) {
                    double start = from;
                    var change = changes.stream().filter(entry -> !entry.removed && entry.sourceStart == start
                            && entry.sourceEnd == to).findFirst();
                    if (change.isPresent()) {
                        var e = change.get();
                        spans.add(grid.section(e.id, level, e.name, e.author, e.reason, start, to, 1, 1));
                    } else if (changes.stream().noneMatch(e -> e.removed && intersects(start, to, e))) {
                        double center = (start + to) / 2;
                        var proposal = proposals.stream().filter(p -> p.sourceStart <= center && center < p.sourceEnd
                                && !changedIds.contains(p.id)).findFirst();
                        if (proposal.isPresent()) {
                            var p = proposal.get();
                            String id = start == p.sourceStart ? p.id : p.id + "@" + Double.toString(start);
                            spans.add(grid.section(id, level, p.label, p.author, p.reason, start, to,
                                    start == p.sourceStart ? p.startConfidence : .25,
                                    to == p.sourceEnd ? p.endConfidence : .25));
                        }
                    }
                }
                from = to;
            }
            partitions.put(level, spans);
            effective.addAll(spans);
        }
        return new Result(withParents(effective), automatic, issues, warnings);
    }

    private static void validateOverlap(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            var a = entries.get(i);
            if (a.removed) continue;
            for (int j = i + 1; j < entries.size(); j++) {
                var b = entries.get(j);
                if (b.removed || !intersects(a.sourceStart, a.sourceEnd, b)) continue;
                require(a.level != b.level, FILE, "authored spans overlap: " + a.id + " and " + b.id);
                Entry outer = a.level.ordinal() < b.level.ordinal() ? a : b;
                Entry inner = outer == a ? b : a;
                require(outer.sourceStart <= inner.sourceStart && outer.sourceEnd >= inner.sourceEnd,
                        FILE, "authored child crosses parent: " + inner.id + " and " + outer.id);
            }
        }
    }

    private static void validateIdentity(String id, Author author, String reason) {
        require(id.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,95}"), "id", "expected a 1–96 character section ID");
        require(author == Author.human || author == Author.agent, "author", "expected human or agent");
        require(!reason.isBlank(), "reason", "required");
    }

    public static synchronized Result run(Path work, String action, JsonElement data) throws IOException {
        return run(work, action, data, () -> {}, () -> {});
    }

    static synchronized Result run(Path work, String action, JsonElement data,
                                   ReconstructionReview.Check check, ReconstructionReview.Check saved) throws IOException {
        require(List.of("list", "add", "set", "remove", "rename").contains(action), "action", "unknown sections operation");
        if (action.equals("list")) {
            require(data == null, "list", "takes no payload");
            return load(work);
        }
        require(data != null && (data.isJsonObject() || data.isJsonArray()), action, "expected an object or array");
        var wrapped = new com.google.gson.JsonObject();
        wrapped.addProperty("version", ReconstructionStages.VERSION);
        if (data.isJsonArray()) wrapped.add("entries", data);
        else {
            var array = new com.google.gson.JsonArray();
            array.add(data);
            wrapped.add("entries", array);
        }
        var request = ReconstructionArtifacts.decode(work.resolve(FILE), Request.class, wrapped);
        require(!request.entries.isEmpty(), action, "expected at least one change");

        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            check.run();
            var model = new ReconstructionSections(ReconstructionStages.readScore(work.resolve("score.json")), savedStructure(work));
            var entries = new LinkedHashMap<String, Entry>();
            for (var entry : read(work).entries) entries.put(entry.id, entry);
            var before = model.effective(List.copyOf(entries.values()));
            var seen = new java.util.HashSet<String>();
            for (var change : request.entries) {
                validateIdentity(change.id, change.author, change.reason);
                boolean remove = action.equals("remove") || Boolean.TRUE.equals(change.removed);
                boolean rename = action.equals("rename");
                if (remove || rename) {
                    require(!action.equals("add") && (!rename || !remove), action, "incompatible operation");
                    require(change.level == null && change.sourceStart == null && change.sourceEnd == null,
                            action, "expected id, author, reason and a name for rename");
                    var prior = entries.get(change.id);
                    if (prior == null) {
                        var old = before.select(change.id);
                        prior = new Entry(old.id, old.level, old.label, old.author, old.reason,
                                old.sourceStart, old.sourceEnd, old.startBar, old.endBar, false);
                    }
                    String name = rename ? change.name : prior.name;
                    require(name != null && !name.isBlank() && (!remove || change.name == null), action, "invalid name");
                    require(seen.add(prior.id), action, "duplicate change for " + prior.id);
                    entries.put(prior.id, new Entry(prior.id, prior.level, name, change.author, change.reason,
                            prior.sourceStart, prior.sourceEnd, prior.startBar, prior.endBar, remove));
                } else {
                    require(seen.add(change.id), action, "duplicate change for " + change.id);
                    if (action.equals("add"))
                        require(!entries.containsKey(change.id) && before.sections.stream().noneMatch(s -> s.id.equals(change.id)),
                                action, "ID already exists: " + change.id);
                    require(change.level != null && change.name != null && !change.name.isBlank(), action, "level and name are required");
                    require(change.sourceStart != null && change.sourceEnd != null && change.sourceStart >= 0
                                    && change.sourceEnd > change.sourceStart + EPSILON
                                    && change.sourceEnd <= model.grid.timeline.sourceDuration(),
                            action, "sourceStart and sourceEnd must give a positive span within the run");
                    entries.put(change.id, new Entry(change.id, change.level, change.name, change.author, change.reason,
                            change.sourceStart, change.sourceEnd, model.grid.bar(change.sourceStart),
                            model.grid.bar(change.sourceEnd), false));
                }
            }
            var updated = List.copyOf(entries.values());
            var result = model.effective(updated);
            ReconstructionArtifacts.write(work.resolve(FILE), new Journal(ReconstructionStages.VERSION, updated));
            saved.run();
            return result;
        }
    }

    static void cli(String[] args, PrintStream out) throws IOException {
        require(args.length >= 1 && args.length <= 3, "sections", "expected WORK_DIR or OP WORK_DIR JSON");
        if (args.length == 1 || args.length == 2 && args[0].equals("list")) {
            out.println(JSON.toJson(load(Path.of(args[args.length - 1]))));
        } else {
            require(args.length == 3, "sections", "expected OP WORK_DIR JSON");
            try {
                out.println(JSON.toJson(run(Path.of(args[1]), args[0], JsonParser.parseString(args[2]))));
            } catch (JsonParseException error) {
                throw new IllegalArgumentException("sections: invalid JSON", error);
            }
        }
    }
}
