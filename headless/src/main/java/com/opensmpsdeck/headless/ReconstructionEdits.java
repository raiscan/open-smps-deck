package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.*;
import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;

final class ReconstructionEdits {
    static final String FILE = "edits.json";
    static final String RESULTS = "edit-results.json";

    enum Kind { insertNote, deleteNote, setNote, splitNote, mergeNotes, setRole, pinLane, unmerge, bend, glide, swapRoles, setVelocity }
    enum Status { applied, satisfied, orphaned, invalid, conflict }

    record Span(double start, double end, String section) {
        Span(double start, double end) { this(start, end, null); }

        boolean contains(double time) { return start <= time && time < end; }
        Span union(Span other) {
            return other == null ? this : new Span(Math.min(start, other.start), Math.max(end, other.end));
        }
    }

    record Attack(double at, int pitch, int part) {
        static final double TOLERANCE = .020;

        boolean matches(Note note, double sourceStart) {
            return part == note.part() && pitch == note.pitch() && Math.abs(at - sourceStart) <= TOLERANCE + 1e-9;
        }
    }

    enum Shape { linear, easeIn, easeOut, smooth }

    record Intent(String id, Kind kind, JsonObject json, Integer part, Span span, Integer pitchMin, Integer pitchMax,
                  List<String> noteIds, Attack attack, Double start, Double end, Integer pitch, Integer velocity,
                  Double scale, Boolean noExtension, Role role, Role fromRole,
                  String lane, List<ReconstructionBends.Point> points, List<Attack> selectors,
                  Integer voice, Role others, TimeBase timeBase, Shape shape, Double glideSeconds,
                  Double splitAt, boolean allowDifferentPitches, boolean mergeGlide, ReconstructionLaneType laneType) {
        String insertedId() { return "edit-" + id; }
        String tag() { return "edit:" + id; }

        Stage firstStage() {
            return switch (kind) {
                case insertNote, deleteNote, setNote, splitNote, mergeNotes, bend, glide, setVelocity -> Stage.TRANSCRIBE;
                case setRole, swapRoles, pinLane, unmerge -> Stage.ROLES;
            };
        }

        boolean changesAttack() { return start != null || end != null || pitch != null; }

        double inBase(double source, ReconstructionTimeMap timeline) {
            return timeBase == TimeBase.project ? timeline.project(source) : source;
        }

        double toSource(double time, ReconstructionTimeMap timeline) {
            return timeBase == TimeBase.project ? timeline.source(time) : time;
        }

        boolean matchesSpan(Note note, double sourceStart) {
            return part == note.part() && span.contains(sourceStart)
                    && (pitchMin == null || note.pitch() >= pitchMin)
                    && (pitchMax == null || note.pitch() <= pitchMax);
        }

        Note level(Note note) {
            int level = velocity != null ? velocity : (int) Math.max(1, Math.min(127, Math.round(note.velocity() * scale)));
            return note.withVelocity(level);
        }

        Span explicitSpan() {
            return span != null ? span.section == null ? span : null
                    : start != null && end != null ? new Span(start, end) : null;
        }

        Intent withSpan(Span resolved) {
            return new Intent(id, kind, json, part, resolved, pitchMin, pitchMax, noteIds, attack, start, end, pitch,
                    velocity, scale, noExtension, role, fromRole, lane, points, selectors, voice, others,
                    timeBase, shape, glideSeconds, splitAt, allowDifferentPitches, mergeGlide, laneType);
        }
    }

    record Outcome(String id, Kind op, Status status, List<String> noteIds,
                   @Nullable List<String> missingNoteIds, @Nullable String detail, @Nullable Note original,
                   @Nullable List<Note> removedNotes, @Nullable List<Note> resultNotes,
                   @Nullable ReconstructionLaneType laneType, @Nullable String lane) {
        Outcome(String id, Kind op, Status status, List<String> noteIds, List<String> missingNoteIds,
                String detail, Note original, List<Note> removedNotes, List<Note> resultNotes) {
            this(id, op, status, noteIds, missingNoteIds, detail, original, removedNotes, resultNotes, null, null);
        }

        Outcome(String id, Kind op, Status status, List<String> noteIds,
                List<String> missingNoteIds, String detail, Note original) {
            this(id, op, status, noteIds, missingNoteIds, detail, original, null, null);
        }

        boolean changesTopology() { return op == Kind.splitNote || op == Kind.mergeNotes; }

        String tag() { return "edit:" + id; }

        boolean authorsGate() {
            return status == Status.applied && op != Kind.setVelocity && (op != Kind.setNote || original != null);
        }
    }

    record Journal(int version, Map<String, List<Outcome>> stages, @Nullable String scoreRecipe) {
        static Journal read(Path work) throws IOException {
            Path file = work.resolve(RESULTS);
            return Files.exists(file) ? ReconstructionArtifacts.read(file, Journal.class)
                    : new Journal(VERSION, Map.of(), null);
        }

        Journal through(Stage from) {
            var retained = new LinkedHashMap<String, List<Outcome>>();
            stages.forEach((stage, outcomes) -> {
                if (Stage.parse(stage).ordinal() < from.ordinal()) retained.put(stage, outcomes);
            });
            return new Journal(version, retained, from == Stage.TRANSCRIBE ? null : scoreRecipe);
        }

        Journal record(Path work, Stage stage, List<Outcome> outcomes, ReconstructionEdits edits) throws IOException {
            if (!outcomes.isEmpty()) stages.put(stage.name().toLowerCase(java.util.Locale.ROOT), List.copyOf(outcomes));
            var journal = stage == Stage.TRANSCRIBE ? new Journal(version, stages, edits.scoreRecipe()) : this;
            Path file = work.resolve(RESULTS);
            if (stages.isEmpty() && journal.scoreRecipe == null) Files.deleteIfExists(file);
            else ReconstructionArtifacts.write(file, journal);
            return journal;
        }

        List<Outcome> all() { return stages.values().stream().flatMap(List::stream).toList(); }
    }

    record Resolution(String editId, String noteId, Role incomingRole, Role resultingRole,
                      boolean satisfied, Double sourceStart, Double projectStart) {}

    record Pin(@Nullable String lane, String editId, int order, @Nullable ReconstructionLaneType laneType) {
        Pin(String lane, String editId, int order) { this(lane, editId, order, null); }

        String target() { return laneType == null ? lane : laneType.name(); }

        String tag() { return editId.startsWith("voice:") ? editId : "edit:" + editId; }
    }

    private final List<Intent> intents;

    ReconstructionEdits(List<Intent> intents) { this.intents = List.copyOf(intents); }

    List<Intent> intents() { return intents; }
    boolean isEmpty() { return intents.isEmpty(); }

    String scoreRecipe() {
        var scoreIntents = intents.stream().filter(edit -> edit.firstStage() == Stage.TRANSCRIBE)
                .map(Intent::json).toList();
        return scoreIntents.isEmpty() ? null : ReconstructionArtifacts.fingerprint(scoreIntents);
    }

    static ReconstructionEdits read(Path work) throws IOException {
        Path file = work.resolve(FILE);
        if (!Files.exists(file)) return new ReconstructionEdits(List.of());
        JsonElement raw = ReconstructionArtifacts.readTree(file);
        JsonArray entries = batch(raw, FILE);
        var intents = new ArrayList<Intent>();
        var seen = new LinkedHashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            String at = FILE + ".edits[" + i + "]";
            require(entries.get(i).isJsonObject(), at, "expected an intent object");
            Intent intent = parse(entries.get(i).getAsJsonObject(), at);
            require(seen.add(intent.id), at + ".id", "duplicate edit ID " + intent.id);
            intents.add(intent);
        }
        return new ReconstructionEdits(intents);
    }

    static JsonArray batch(JsonElement raw, String at) {
        require(raw.isJsonObject(), at, "expected a versioned object");
        JsonObject object = raw.getAsJsonObject();
        fields(object, at, Set.of("version", "edits"));
        require(integer(object, "version", at, 1, 1) != null, at + ".version", "required");
        return array(object, "edits", at);
    }

    void write(Path work) throws IOException {
        ReconstructionArtifacts.write(work.resolve(FILE), Map.of("version", VERSION,
                "edits", intents.stream().map(Intent::json).toList()));
    }

    static Intent parse(JsonObject json, String at) {
        String id = string(json, "id", at);
        require(id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"), at + ".id", "expected 1–96 letters, digits, dots, underscores or hyphens");
        string(json, "reason", at);
        Kind kind = choice(Kind.class, json, "op", at);
        var allowed = new LinkedHashSet<>(Set.of("id", "reason", "op", "timeBase"));
        allowed.addAll(switch (kind) {
            case insertNote -> List.of("part", "start", "end", "pitch", "velocity", "lane", "role");
            case deleteNote -> List.of("noteId", "selector");
            case setNote -> List.of("noteId", "selector", "start", "end", "pitch", "velocity", "noExtension");
            case splitNote -> List.of("noteId", "selector", "at", "pitch");
            case mergeNotes -> List.of("noteIds", "selectors", "span", "part", "pitch",
                    "allowDifferentPitches", "glide");
            case setRole -> List.of("noteIds", "selector", "span", "part", "pitchMin", "pitchMax", "fromRole", "role", "lane", "selectors", "voice", "others");
            case swapRoles -> List.of("span", "part", "from", "to", "voice");
            case setVelocity -> List.of("span", "part", "pitchMin", "pitchMax", "velocity", "scale", "voice");
            case pinLane -> List.of("noteIds", "selector", "selectors", "span", "part", "pitchMin",
                    "pitchMax", "voice", "lane", "laneType");
            case unmerge -> List.of("noteIds", "selector", "span", "part", "pitchMin", "pitchMax", "voice");
            case bend -> List.of("noteId", "selector", "points");
            case glide -> List.of("fromNoteId", "toNoteId", "shape", "seconds");
        });
        fields(json, at, allowed);

        JsonObject target = json;
        String targetAt = at;
        Attack attack = null;
        if (json.has("selector")) {
            targetAt = at + ".selector";
            require(json.get("selector").isJsonObject(), targetAt, "expected a selector object");
            target = json.getAsJsonObject("selector");
            for (String key : List.of("noteId", "noteIds", "span", "part", "pitchMin", "pitchMax", "fromRole"))
                require(!json.has(key), targetAt, "cannot combine selector with " + key);
            if (target.has("at")) {
                attack = attack(target, targetAt, null);
            } else {
                require(kind == Kind.setRole || kind == Kind.unmerge || kind == Kind.pinLane, targetAt, "expected at, pitch and part");
                fields(target, targetAt, kind == Kind.setRole
                        ? Set.of("span", "part", "pitchMin", "pitchMax", "fromRole")
                        : Set.of("span", "part", "pitchMin", "pitchMax"));
            }
        }

        Integer part = integer(target, "part", targetAt, 0, Integer.MAX_VALUE);
        Integer pitch = integer(json, "pitch", at, 0, 127);
        Integer velocity = integer(json, "velocity", at, 1, 127);
        Double scale = json.has("scale") ? number(json.get("scale"), at + ".scale") : null;
        Boolean noExtension = null;
        if (json.has("noExtension")) {
            var flag = json.get("noExtension");
            require(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean(),
                    at + ".noExtension", "expected a boolean");
            noExtension = flag.getAsBoolean();
        }
        Double splitAt = time(json, "at", at);
        boolean allowDifferentPitches = flag(json, "allowDifferentPitches", at);
        boolean mergeGlide = flag(json, "glide", at);
        Integer min = integer(target, "pitchMin", targetAt, 0, 127);
        Integer max = integer(target, "pitchMax", targetAt, 0, 127);
        require(min == null || max == null || min <= max, targetAt + ".pitchMax", "must be at least pitchMin");
        Double start = time(json, "start", at), end = time(json, "end", at);
        require(start == null || end == null || end > start, at + ".end", "must follow start");
        Role role = json.has("role") ? choice(Role.class, json, "role", at) : null;
        Role fromRole = target.has("fromRole") ? choice(Role.class, target, "fromRole", targetAt) : null;
        String lane = json.has("lane") ? string(json, "lane", at) : null;
        ReconstructionLaneType laneType = json.has("laneType") ? choice(ReconstructionLaneType.class, json, "laneType", at) : null;
        require(lane == null || ReconstructionAllocation.FM.contains(lane) || ReconstructionAllocation.PSG.contains(lane)
                || lane.equals("DAC"), at + ".lane", "expected FM1–FM5, PSG1–PSG3 or DAC");
        require(lane == null || role != Role.drop, at + ".lane", "cannot pin a dropped role");

        var selectors = new ArrayList<Attack>();
        if (json.has("selectors")) {
            for (String key : List.of("selector", "noteIds", "span", "pitchMin", "pitchMax"))
                require(!json.has(key), at + ".selectors", "cannot combine selectors with " + key);
            var values = array(json, "selectors", at);
            require(!values.isEmpty(), at + ".selectors", "expected at least one point selector");
            for (int i = 0; i < values.size(); i++) {
                String where = at + ".selectors[" + i + "]";
                require(values.get(i).isJsonObject(), where, "expected a selector object");
                selectors.add(attack(values.get(i).getAsJsonObject(), where, part));
            }
        }
        TimeBase base = json.has("timeBase") ? choice(TimeBase.class, json, "timeBase", at) : TimeBase.source;
        Role others = json.has("others") ? choice(Role.class, json, "others", at) : null;
        Shape shape = json.has("shape") ? choice(Shape.class, json, "shape", at) : Shape.linear;
        Double glideSeconds = time(json, "seconds", at);
        Span span = null;
        List<String> ids = List.of();
        switch (kind) {
            case insertNote -> {
                require(part != null, at + ".part", "required");
                require(start != null, at + ".start", "required");
                require(end != null, at + ".end", "required");
                require(pitch != null, at + ".pitch", "required");
            }
            case deleteNote, setNote, splitNote, bend -> {
                if (attack == null) ids = List.of(string(json, "noteId", at));
                require(kind != Kind.splitNote || splitAt != null, at + ".at", "split time is required");
                require(kind != Kind.setNote || start != null || end != null || pitch != null
                                || velocity != null || noExtension != null, at,
                        "setNote requires start, end, pitch, velocity or noExtension");
            }
            case mergeNotes -> {
                if (json.has("noteIds")) {
                    require(part == null && !json.has("span") && pitch == null, at + ".noteIds",
                            "use noteIds, selectors, or span with part and pitch");
                    ids = noteIds(json, at, 2);
                } else if (!selectors.isEmpty()) {
                    require(selectors.size() >= 2 && pitch == null, at + ".selectors",
                            "expected at least two point selectors without a top-level pitch");
                } else {
                    span = span(json, at, part);
                    require(pitch != null, at + ".pitch", "required with span");
                    min = max = pitch;
                }
            }
            case glide -> {
                ids = List.of(string(json, "fromNoteId", at), string(json, "toNoteId", at));
                require(!ids.getFirst().equals(ids.getLast()), at + ".toNoteId", "expected two distinct note IDs");
                require(glideSeconds == null || glideSeconds > 0, at + ".seconds", "expected a positive duration");
            }
            case swapRoles -> {
                fromRole = choice(Role.class, json, "from", at);
                role = choice(Role.class, json, "to", at);
                require(fromRole != role && tonalRole(fromRole) && tonalRole(role), at,
                        "from and to must be distinct tonal roles");
                span = span(json, at, part);
            }
            case setVelocity -> {
                require((velocity == null) != (scale == null), at, "choose velocity or scale");
                require(scale == null || scale >= 0, at + ".scale", "expected a nonnegative factor");
                span = span(json, at, part);
            }
            case setRole, pinLane, unmerge -> {
                require(kind != Kind.setRole || role != null, at + ".role", "required");
                require(kind != Kind.pinLane || (lane == null) != (laneType == null), at, "choose lane or laneType");
                if (attack != null || !selectors.isEmpty()) break;
                if (json.has("noteIds")) {
                    require(!json.has("span") && part == null && min == null && max == null, at + ".noteIds",
                            "use noteIds or span with part and optional pitch bounds");
                    ids = noteIds(json, at, 1);
                } else span = span(target, targetAt, part);
            }
        }
        var points = new ArrayList<ReconstructionBends.Point>();
        if (kind == Kind.bend) {
            var values = array(json, "points", at);
            require(values.size() >= 2 && values.size() <= 512, at + ".points", "expected 2–512 [seconds, semitones] pairs");
            double prior = -1;
            for (int i = 0; i < values.size(); i++) {
                String where = at + ".points[" + i + "]";
                require(values.get(i).isJsonArray() && values.get(i).getAsJsonArray().size() == 2, where,
                        "expected [seconds, semitones]");
                var pair = values.get(i).getAsJsonArray();
                double seconds = time(pair.get(0), where + "[0]");
                require(seconds > prior, where, "time must increase");
                double semitones = number(pair.get(1), where + "[1]");
                require(Math.abs(semitones) <= ReconstructionBendEncoding.MAX_CENTS / 100.0, where,
                        "semitone excursion exceeds S3K pitch range");
                points.add(new ReconstructionBends.Point(seconds, (int) Math.round(semitones * 100)));
                prior = seconds;
            }
        }
        Integer voice = null;
        if (json.has("voice")) {
            require(span != null, at + ".voice", "requires a span");
            var value = json.get("voice");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                voice = switch (value.getAsString()) {
                    case "top" -> 0;
                    case "bottom" -> -1;
                    default -> throw new IllegalArgumentException(at + ".voice: expected top, bottom or a zero-based index");
                };
            } else voice = integer(json, "voice", at, 0, Integer.MAX_VALUE);
        }
        require(others == null || voice != null, at + ".others", "requires a voice filter");
        require(others == null || lane == null, at + ".lane", "use a separate lane pin when assigning others");
        return new Intent(id, kind, json.deepCopy(), part, span, min, max, ids, attack, start, end, pitch,
                velocity, scale, noExtension, role, fromRole, lane, List.copyOf(points), List.copyOf(selectors),
                voice, others, base, shape, glideSeconds, splitAt, allowDifferentPitches, mergeGlide, laneType);
    }

    private static boolean flag(JsonObject json, String key, String at) {
        var value = json.get(key);
        if (value == null) return false;
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), at + "." + key, "expected a boolean");
        return value.getAsBoolean();
    }

    private static List<String> noteIds(JsonObject json, String at, int minimum) {
        var values = array(json, "noteIds", at);
        require(values.size() >= minimum, at + ".noteIds", "expected at least " + minimum + " note ID(s)");
        var unique = new LinkedHashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            String where = at + ".noteIds[" + i + "]";
            String value = string(values.get(i), where);
            require(unique.add(value), where, "duplicate note ID " + value);
        }
        return List.copyOf(unique);
    }

    private static Attack attack(JsonObject target, String at, Integer defaultPart) {
        fields(target, at, Set.of("at", "pitch", "part"));
        Integer pitch = integer(target, "pitch", at, 0, 127);
        Integer part = integer(target, "part", at, 0, Integer.MAX_VALUE);
        if (part == null) part = defaultPart;
        require(pitch != null && part != null, at, "expected at, pitch and part");
        return new Attack(time(target.get("at"), at + ".at"), pitch, part);
    }

    private static Map<String, Boolean> register(Intent edit, List<Note> notes,
                                                 Predicate<String> acceptsTarget) {
        var ranked = new LinkedHashMap<String, Boolean>();
        if (edit.voice == null) {
            for (var note : notes) ranked.put(note.id(), true);
            return ranked;
        }
        for (var cluster : ReconstructionOnsetClusters.group(notes, Note::part, Note::start)) {
            var ordered = cluster.notes().stream().sorted(Comparator.comparingInt(Note::pitch).reversed()
                    .thenComparingDouble(Note::start).thenComparing(Note::id)).toList();
            int index = edit.voice == -1 ? ordered.size() - 1 : edit.voice;
            if (index >= ordered.size() || !acceptsTarget.test(ordered.get(index).id())) continue;
            for (int i = 0; i < ordered.size(); i++)
                if (i == index || edit.others != null) ranked.put(ordered.get(i).id(), i == index);
        }
        return ranked;
    }

    private static boolean tonalRole(Role role) {
        return role != Role.drop && role != Role.drums;
    }

    private static Span span(JsonObject target, String at, Integer part) {
        require(part != null, at + ".part", "required with span");
        var value = target.get("span");
        if (value != null && value.isJsonObject()) {
            var selector = value.getAsJsonObject();
            fields(selector, at + ".span", Set.of("section"));
            return new Span(0, 0, string(selector, "section", at + ".span"));
        }
        var values = array(target, "span", at);
        require(values.size() == 2, at + ".span", "expected [start, end] in source seconds");
        double from = time(values.get(0), at + ".span[0]");
        double to = time(values.get(1), at + ".span[1]");
        require(to > from, at + ".span[1]", "must follow span start");
        return new Span(from, to);
    }

    ReconstructionEdits bindSections(Path work, Score score, ReconstructionStructure.Result structure, Stage stage,
                                    List<Outcome> outcomes) throws IOException {
        if (intents.stream().noneMatch(edit -> usesSection(edit, stage))) return this;
        var form = structure == null ? ReconstructionSections.load(work, score)
                : ReconstructionSections.load(work, score, structure);
        var bound = new ArrayList<Intent>();
        for (var edit : intents) {
            if (!usesSection(edit, stage)) {
                bound.add(edit);
                continue;
            }
            try {
                var selected = form.select(edit.span.section);
                Span range = edit.timeBase == TimeBase.project ? new Span(selected.projectStart(), selected.projectEnd())
                        : new Span(selected.sourceStart(), selected.sourceEnd());
                bound.add(edit.withSpan(range));
            } catch (IllegalArgumentException error) {
                outcomes.add(new Outcome(edit.id, edit.kind, Status.invalid, List.of(), null, error.getMessage(), null));
            }
        }
        return new ReconstructionEdits(bound);
    }

    private static boolean usesSection(Intent edit, Stage stage) {
        return edit.firstStage() == stage && edit.span != null && edit.span.section != null;
    }

    Score applyScore(Score score, List<Outcome> outcomes) {
        return applyScore(score, outcomes, Set.of());
    }

    Score applyScore(Path work, Score score, List<Outcome> outcomes) throws IOException {
        var scoped = new LinkedHashSet<String>();
        Path voicing = work.resolve("voicing.json");
        if (intents.stream().anyMatch(edit -> edit.kind == Kind.splitNote || edit.kind == Kind.mergeNotes)
                && Files.isRegularFile(voicing)) {
            for (var lane : ReconstructionArtifacts.read(voicing, ReconstructionVoicing.Result.class).lanes().values()) {
                if (lane.overrides() == null) continue;
                for (var scope : lane.overrides()) if (scope.noteIds() != null) scoped.addAll(scope.noteIds());
            }
        }
        return applyScore(score, outcomes, scoped);
    }

    private Score applyScore(Score score, List<Outcome> outcomes, Set<String> scoped) {
        if (intents.stream().noneMatch(edit -> edit.firstStage() == Stage.TRANSCRIBE)) return score;
        var notes = new LinkedHashMap<String, Note>();
        for (var note : score.notes()) notes.put(note.id(), note);
        var changed = new LinkedHashSet<String>();
        var origins = Map.copyOf(notes);
        var retired = new LinkedHashMap<String, Outcome>();
        var merged = new ArrayList<ScoreDecision>();
        var timeline = ReconstructionTimeMap.of(score);
        for (Intent edit : intents) {
            if (edit.firstStage() != Stage.TRANSCRIBE) continue;
            if (edit.kind == Kind.mergeNotes) {
                var outcome = merge(edit, score, notes, origins, retired, timeline, scoped);
                outcomes.add(outcome);
                if (outcome.status == Status.applied) {
                    var survivor = outcome.noteIds.getFirst();
                    changed.add(survivor);
                    retired.put(survivor, outcome);
                    for (Note removed : outcome.removedNotes) {
                        changed.add(removed.id());
                        retired.put(removed.id(), outcome);
                        merged.add(new ScoreDecision(removed, outcome.tag(), survivor));
                    }
                }
                continue;
            }
            if (edit.kind == Kind.setVelocity) {
                var affected = List.copyOf(register(edit, notes.values().stream()
                        .filter(note -> edit.matchesSpan(note, edit.inBase(note.start(), timeline))).toList(), id -> true).keySet());
                for (String id : affected) notes.computeIfPresent(id, (key, note) -> edit.level(note));
                outcomes.add(affected.isEmpty() ? orphan(edit, List.of())
                        : new Outcome(edit.id, edit.kind, Status.applied, List.copyOf(affected), null, null, null));
                continue;
            }
            List<String> requested = edit.attack == null ? edit.noteIds
                    : notes.values().stream().filter(note -> edit.attack.matches(note, edit.inBase(note.start(), timeline))).map(Note::id).toList();
            if (edit.attack != null && requested.size() != 1) {
                // Deletions can also resolve against new notes generated during roles.
                if (edit.kind != Kind.deleteNote || !requested.isEmpty())
                    outcomes.add(invalidAttack(edit, requested));
                continue;
            }
            String id = edit.kind == Kind.insertNote ? edit.insertedId() : requested.getFirst();
            Note before = notes.get(id);
            if (before == null && edit.kind != Kind.insertNote) {
                if (edit.kind != Kind.deleteNote) outcomes.add(orphan(edit, edit.noteIds));
                continue;
            }
            String at = FILE + "." + edit.id;
            try {
                switch (edit.kind) {
                    case insertNote -> {
                        var part = part(score, edit.part);
                        if (part == null) {
                            outcomes.add(orphan(edit, List.of()));
                            continue;
                        }
                        require(before == null, at + ".id", "note ID already exists: " + id);
                        var note = new Note(id, edit.part, edit.toSource(edit.start, timeline),
                                edit.toSource(edit.end, timeline), edit.pitch,
                                edit.velocity == null ? 96 : edit.velocity, "edit", 1);
                        validateNote(note, score, part, at);
                        validateRoleLane(score, note, edit.role, edit.lane, at);
                        notes.put(id, note);
                    }
                    case splitNote -> {
                        if (scoped.contains(id) || before.bend() != null && before.bend().editReason() != null) {
                            outcomes.add(conflict(edit, List.of(id), scoped.contains(id)
                                    ? "voicing.json has a scoped override for " + id
                                    : "note has an authored bend from " + before.bend().editReason()));
                            continue;
                        }
                        var outcome = split(edit, score, notes, before, at, timeline);
                        outcomes.add(outcome);
                        changed.add(id);
                        retired.put(id, outcome);
                        continue;
                    }
                    case deleteNote -> notes.remove(id);
                    case setNote -> {
                        if (!edit.changesAttack()) {
                            if (edit.velocity != null) notes.put(id, edit.level(before));
                            break;
                        }
                        require(before.run() == null, at + ".noteId", "setNote expects a single attack");
                        var note = new Note(id, before.part(), edit.start == null ? before.start() : edit.toSource(edit.start, timeline),
                                edit.end == null ? before.end() : edit.toSource(edit.end, timeline),
                                edit.pitch == null ? before.pitch() : edit.pitch,
                                edit.velocity == null ? before.velocity() : edit.velocity, before.source(), before.confidence());
                        validateNote(note, score, part(score, note.part()), at);
                        notes.put(id, note);
                    }
                    case bend -> notes.put(id, before.withBend(bend(edit, before, at, edit.points.stream()
                            .map(point -> new ReconstructionBends.Point(edit.toSource(point.time(), timeline), point.cents()))
                            .toList(), timeline)));
                    case glide -> {
                        Note target = notes.get(requested.getLast());
                        if (target == null) {
                            outcomes.add(orphan(edit, List.of(requested.getLast())));
                            continue;
                        }
                        require(!part(score, before.part()).drums(), at + ".fromNoteId", "glide requires a tonal part");
                        notes.put(id, glide(edit, before, target, at, timeline));
                    }
                    default -> throw new AssertionError(edit.kind);
                }
                boolean timingOrPitch = edit.kind != Kind.setNote || edit.changesAttack();
                if (timingOrPitch) changed.add(id);
                var outcome = new Outcome(edit.id, edit.kind, Status.applied, List.of(id), null, null,
                        edit.kind == Kind.deleteNote || edit.kind == Kind.glide
                                || edit.kind == Kind.setNote && timingOrPitch ? before : null);
                outcomes.add(outcome);
                if (edit.kind == Kind.deleteNote) retired.put(id, outcome);
            } catch (IllegalArgumentException error) {
                outcomes.add(invalid(edit, id, error));
            }
        }
        if (changed.isEmpty() && notes.values().stream().toList().equals(score.notes())) return score;
        var bursts = score.retriggers() == null ? null : score.retriggers().stream()
                .filter(burst -> !changed.contains(burst.anchor())).toList();
        var voices = score.figureVoices() == null ? null : new LinkedHashMap<>(score.figureVoices());
        if (voices != null) {
            changed.forEach(voices::remove);
            if (score.retriggers() != null) score.retriggers().stream().filter(burst -> changed.contains(burst.anchor()))
                    .forEach(burst -> voices.remove(burst.figure().id()));
        }
        List<ScoreDecision> decisions = null;
        if (score.scoreDecisions() != null || !merged.isEmpty()) {
            decisions = new ArrayList<>();
            if (score.scoreDecisions() != null) decisions.addAll(score.scoreDecisions());
            decisions.addAll(merged);
        }
        var activeDecisions = decisions == null ? null : decisions.stream()
                .filter(decision -> notes.containsKey(decision.successorId())).toList();
        return score.withInputs(score.inputs(), score.bars(), notes.values().stream().sorted(NOTE_ORDER).toList(),
                score.tempo(), score.midiBars(), score.beatMap(), voices, bursts, activeDecisions);
    }

    private static Outcome split(Intent edit, Score score, Map<String, Note> notes, Note before, String at,
                                 ReconstructionTimeMap timeline) {
        require(before.run() == null && !"external".equals(before.source()), at + ".noteId",
                "split expects an effective single attack");
        double cut = edit.toSource(edit.splitAt, timeline);
        require(before.start() < cut && cut < before.end(), at + ".at", "split time must be inside the note gate");
        int frames = timeline.frames();
        int cutTick = timeline.projectNote(before.withTiming(cut, before.end())).startTick(frames);
        var originalGate = timeline.projectNote(before);
        require(cutTick > originalGate.startTick(frames) && cutTick < originalGate.endTick(frames),
                at + ".at", "each half must occupy at least one project tick");
        var left = new Note(edit.insertedId() + "-left", before.part(), before.start(), cut, before.pitch(),
                before.velocity(), "edit", before.confidence());
        var right = new Note(edit.insertedId() + "-right", before.part(), cut, before.end(),
                edit.pitch == null ? before.pitch() : edit.pitch, before.velocity(), "edit", before.confidence());
        require(!notes.containsKey(left.id()) && !notes.containsKey(right.id()), at + ".id",
                "derived split note ID already exists");
        validateNote(left, score, part(score, before.part()), at);
        validateNote(right, score, part(score, before.part()), at);
        notes.remove(before.id());
        notes.put(left.id(), left);
        notes.put(right.id(), right);
        return new Outcome(edit.id, edit.kind, Status.applied, List.of(left.id(), right.id()), null,
                "split " + before.id() + " into two attacks", null, List.of(before), List.of(left, right));
    }

    private Outcome merge(Intent edit, Score score, Map<String, Note> notes, Map<String, Note> origins,
                          Map<String, Outcome> retired, ReconstructionTimeMap timeline, Set<String> scoped) {
        var requested = new LinkedHashSet<>(edit.noteIds);
        if (edit.span != null) {
            for (var note : notes.values())
                if (targets(edit, note, timeline)) requested.add(note.id());
            // A prior deletion must not quietly reduce the set selected by a span.
            for (var outcome : retired.values())
                if (outcome.op == Kind.deleteNote && targets(edit, outcome.original, timeline))
                    requested.add(outcome.original.id());
        }
        if (!edit.selectors.isEmpty()) {
            for (var point : edit.selectors) {
                var found = notes.values().stream().filter(note -> point.matches(note, edit.inBase(note.start(), timeline)))
                        .map(Note::id).toList();
                if (found.isEmpty()) {
                    for (var entry : retired.entrySet()) {
                        var outcome = entry.getValue();
                        Note original = outcome.op == Kind.deleteNote ? outcome.original : origins.get(entry.getKey());
                        if (original != null && point.matches(original, edit.inBase(original.start(), timeline)))
                            return conflict(edit, List.of(original.id()), "target already changed by " + outcome.tag());
                    }
                }
                if (found.size() != 1) return invalidAttack(edit, found);
                requested.add(found.getFirst());
            }
        }
        var ids = List.copyOf(requested);
        for (String id : ids) {
            var earlier = retired.get(id);
            if (earlier != null) return conflict(edit, ids, id + " already changed by " + earlier.tag());
        }
        var missing = ids.stream().filter(id -> !notes.containsKey(id)).toList();
        if (!missing.isEmpty() || ids.isEmpty()) return orphan(edit, missing);
        var members = ids.stream().map(notes::get).sorted(NOTE_ORDER.thenComparing(Note::id)).toList();
        var first = members.getFirst();
        var removed = members.subList(1, members.size());
        String at = FILE + "." + edit.id;
        try {
            require(members.size() >= 2, at, "merge requires at least two distinct notes");
            require(members.stream().allMatch(note -> note.part() == first.part()), at,
                    "cannot merge notes from different parts");
            require(!part(score, first.part()).drums()
                            && members.stream().noneMatch(note -> note.run() != null || "external".equals(note.source())),
                    at, "merge requires effective single attacks in a tonal part");
            require(edit.allowDifferentPitches || edit.mergeGlide
                            || members.stream().allMatch(note -> note.pitch() == first.pitch()),
                    at, "notes must have the same pitch unless allowDifferentPitches or glide is true");
            String conflict = mergeConflict(edit, members, origins, timeline, scoped);
            if (conflict != null) return conflict(edit, ids, conflict);
            double end = members.stream().mapToDouble(Note::end).max().orElseThrow();
            var merged = new Note(first.id(), first.part(), first.start(), end, first.pitch(), first.velocity(),
                    first.source(), first.confidence());
            validateNote(merged, score, part(score, merged.part()), at);
            if (edit.mergeGlide) merged = mergeGlide(edit, merged, members, at, timeline);
            removed.forEach(note -> notes.remove(note.id()));
            notes.put(first.id(), merged);
            return new Outcome(edit.id, edit.kind, Status.applied, List.of(first.id()), null,
                    "merged " + removed.stream().map(Note::id).toList() + " into " + first.id(), null, List.copyOf(removed), List.of(merged));
        } catch (IllegalArgumentException error) {
            return new Outcome(edit.id, edit.kind, Status.invalid, ids, null, error.getMessage(), null);
        }
    }

    private String mergeConflict(Intent merge, List<Note> members, Map<String, Note> origins,
                                 ReconstructionTimeMap timeline, Set<String> scoped) {
        var first = members.getFirst();
        var removed = members.subList(1, members.size());
        boolean sliding = merge.mergeGlide && removed.stream().anyMatch(note -> note.pitch() != first.pitch());
        for (var note : removed) {
            if (scoped.contains(note.id())) return "voicing.json has a scoped override for " + note.id();
        }
        for (var other : intents) {
            if (other == merge || other.kind == Kind.mergeNotes || other.kind == Kind.insertNote) continue;
            if (sliding && (other.laneType != null && other.laneType != ReconstructionLaneType.FM
                    || other.lane != null && !ReconstructionLaneType.FM.lanes().contains(other.lane))
                    && (targets(other, first, timeline) || targets(other, origins.get(first.id()), timeline)))
                return "glide requires FM and conflicts with " + other.tag() + " targeting " + first.id();
            // A uniform span edit can still be applied to the resulting sustain.
            if ((other.kind == Kind.setVelocity || other.kind == Kind.setRole || other.kind == Kind.swapRoles)
                    && other.span != null && other.voice == null && other.fromRole == null
                    && members.stream().allMatch(note -> targets(other, note, timeline))) continue;
            boolean changesAnchorGate = switch (other.kind) {
                case deleteNote, splitNote, bend, glide, unmerge -> true;
                case setNote -> other.changesAttack();
                default -> false;
            };
            for (var note : changesAnchorGate ? members : removed) {
                if (targets(other, note, timeline) || targets(other, origins.get(note.id()), timeline))
                    return "conflicts with " + other.tag() + " targeting " + note.id();
            }
        }
        return null;
    }

    private static boolean targets(Intent edit, Note note, ReconstructionTimeMap timeline) {
        if (note == null) return false;
        double start = edit.inBase(note.start(), timeline);
        return edit.noteIds.contains(note.id()) || edit.attack != null && edit.attack.matches(note, start)
                || edit.selectors.stream().anyMatch(point -> point.matches(note, start))
                || edit.span != null && edit.span.section == null && edit.matchesSpan(note, start);
    }

    private static Note mergeGlide(Intent edit, Note merged, List<Note> members, String at,
                                   ReconstructionTimeMap timeline) {
        var points = new ArrayList<ReconstructionBends.Point>();
        points.add(new ReconstructionBends.Point(merged.start(), 0));
        for (int i = 1; i < members.size(); i++) {
            var previous = members.get(i - 1);
            var next = members.get(i);
            if (previous.pitch() == next.pitch()) continue;
            require(ReconstructionOverrides.tick(timeline.project(next.start()))
                            - ReconstructionOverrides.tick(timeline.project(previous.start())) >= 4,
                    at + ".glide", "pitch changes require at least four project ticks between attacks");
            double begin = Math.max(previous.start(),
                    edit.toSource(edit.inBase(next.start(), timeline) - .12, timeline));
            if (begin > points.getLast().time())
                points.add(new ReconstructionBends.Point(begin, (previous.pitch() - merged.pitch()) * 100));
            points.add(new ReconstructionBends.Point(next.start(), (next.pitch() - merged.pitch()) * 100));
        }
        return points.size() == 1 ? merged : merged.withBend(bend(edit, merged, at, points, timeline));
    }

    private static Outcome conflict(Intent edit, List<String> ids, String detail) {
        return new Outcome(edit.id, edit.kind, Status.conflict, ids, null, detail, null);
    }

    ReconstructionRoles.Result assignRoles(Score score, ReconstructionStructure.Result structure, double rate,
                                           ReconstructionRolePlan plan, ReconstructionLeadContinuity continuity,
                                           List<Outcome> scoreOutcomes, List<Outcome> outcomes) {
        return assignRoles(score, structure, rate, plan, continuity, scoreOutcomes, outcomes, null);
    }

    ReconstructionRoles.Result assignRoles(Score score, ReconstructionStructure.Result structure, double rate,
                                           ReconstructionRolePlan plan, ReconstructionLeadContinuity continuity,
                                           List<Outcome> scoreOutcomes, List<Outcome> outcomes,
                                           List<Resolution> resolutions) {
        var timeline = ReconstructionTimeMap.of(score);
        var written = new LinkedHashSet<String>();
        var separate = new LinkedHashSet<String>();
        var suppressed = new ArrayList<Note>();
        var extension = new LinkedHashMap<String, Boolean>();
        var byId = new LinkedHashMap<String, Intent>();
        for (Intent edit : intents) byId.put(edit.id, edit);
        for (Outcome outcome : scoreOutcomes) {
            if (outcome.authorsGate()) {
                written.addAll(outcome.noteIds);
                if (outcome.changesTopology()) separate.addAll(outcome.noteIds);
                if (outcome.removedNotes != null)
                    outcome.removedNotes.forEach(note -> suppressed.add(timeline.projectNote(note)));
                if (outcome.original != null) suppressed.add(timeline.projectNote(outcome.original));
            }
            var edit = byId.get(outcome.id);
            if (outcome.status == Status.applied && edit != null && edit.noExtension != null)
                extension.put(outcome.noteIds.getFirst(), edit.noExtension);
        }
        var fixedGates = new LinkedHashSet<>(written);
        extension.forEach((id, blocked) -> { if (blocked) fixedGates.add(id); });
        var roles = ReconstructionRoles.assign(structure, score.source().parts(), rate, plan,
                score.usesBeatGrid() ? score.beatMap() : null, continuity, score.inputs().leadPart(), fixedGates, separate,
                note -> !written.contains(note.id()) && ReconstructionStructure.suppressed(suppressed, note, note.start(), .035));
        return applyRoles(score, structure, roles, scoreOutcomes, outcomes, resolutions);
    }

    ReconstructionRoles.Result applyRoles(Score score, ReconstructionStructure.Result structure,
                                          ReconstructionRoles.Result roles, List<Outcome> scoreOutcomes,
                                          List<Outcome> outcomes) {
        return applyRoles(score, structure, roles, scoreOutcomes, outcomes, null);
    }

    private ReconstructionRoles.Result applyRoles(Score score, ReconstructionStructure.Result structure,
                                                  ReconstructionRoles.Result roles, List<Outcome> scoreOutcomes,
                                                  List<Outcome> outcomes, List<Resolution> resolutions) {
        if (isEmpty()) return roles;
        var timeline = ReconstructionTimeMap.of(score);
        var written = new LinkedHashMap<String, Note>();
        for (var note : structure.notes()) written.put(note.id(), note);
        var decisions = new LinkedHashMap<String, ReconstructionRoles.Decision>();
        for (var decision : roles.notes()) decisions.put(decision.note().id(), decision);
        var mergeGroups = new LinkedHashMap<String, List<String>>();
        for (var decision : roles.notes()) {
            if (decision.mergedInto() != null) {
                mergeGroups.computeIfAbsent(decision.mergedInto(), ignored -> new ArrayList<>())
                        .add(decision.note().id());
            }
        }
        var pins = new LinkedHashMap<String, Pin>();
        var restored = new LinkedHashSet<String>();
        var deleted = new LinkedHashSet<String>();
        var scoreResults = new LinkedHashMap<String, Outcome>();
        for (var outcome : scoreOutcomes) scoreResults.put(outcome.id, outcome);
        int order = 0;
        for (Intent edit : intents) {
            order++;
            var scoreResult = scoreResults.get(edit.id);
            if (edit.kind == Kind.mergeNotes && edit.mergeGlide && scoreResult != null
                    && scoreResult.status == Status.applied) {
                String id = scoreResult.noteIds.getFirst();
                var choice = decisions.get(id);
                if (choice != null && choice.role() != Role.drop && choice.note().bend() != null
                        && edit.tag().equals(choice.note().bend().editReason()))
                    pins.putIfAbsent(id, new Pin(null, edit.id, order, ReconstructionLaneType.FM));
            }
            if (edit.kind == Kind.splitNote || edit.kind == Kind.mergeNotes || edit.kind == Kind.setVelocity
                    || edit.kind == Kind.setNote && !edit.changesAttack()) continue;
            boolean deferredDelete = edit.kind == Kind.deleteNote && (scoreResult == null || scoreResult.status == Status.orphaned);
            if (edit.kind == Kind.deleteNote && !deferredDelete) continue;
            boolean scoreEdit = edit.firstStage() == Stage.TRANSCRIBE && !deferredDelete;
            if (scoreEdit && edit.kind != Kind.insertNote
                    && (scoreResult == null || scoreResult.status != Status.applied)) continue;
            // Insert metadata can also be applied when resuming at roles with an existing edited score.
            if (edit.kind == Kind.insertNote && !decisions.containsKey(edit.insertedId())) continue;
            var selectedRoles = new LinkedHashMap<String, Role>();
            List<String> requested;
            if (scoreEdit) {
                requested = edit.kind == Kind.insertNote ? List.of(edit.insertedId()) : scoreResult.noteIds;
            } else if (edit.span != null || edit.attack != null || !edit.selectors.isEmpty()) {
                var candidates = new ArrayList<Note>();
                for (var decision : decisions.values()) {
                    String id = decision.note().id();
                    if (deleted.contains(id) || deferredDelete && written.containsKey(id)) continue;
                    candidates.add(timeline.sourceNote(written.getOrDefault(id, decision.note())));
                }
                if (edit.span != null) {
                    var ranked = register(edit, candidates.stream()
                            .filter(note -> edit.matchesSpan(note, edit.inBase(note.start(), timeline))).toList(),
                            id -> edit.kind != Kind.setRole || matchesRole(edit, decisions.get(id).role(), edit.role));
                    var matching = new ArrayList<String>();
                    var displaced = new ArrayList<String>();
                    for (var entry : ranked.entrySet()) {
                        String id = entry.getKey();
                        Role incoming = decisions.get(id).role();
                        Role target = entry.getValue() ? edit.role : edit.others;
                        if (edit.kind == Kind.swapRoles && edit.role == incoming) displaced.add(id);
                        else if (!entry.getValue() || matchesRole(edit, incoming, target)) {
                            matching.add(id);
                            if (target != null) selectedRoles.put(id, target);
                        }
                    }
                    if (!matching.isEmpty() && edit.kind == Kind.swapRoles) {
                        displaced.forEach(id -> selectedRoles.put(id, Role.harmony));
                        matching.addAll(displaced);
                    }
                    requested = matching;
                } else {
                    var points = edit.attack != null ? List.of(edit.attack) : edit.selectors;
                    var matching = new LinkedHashSet<String>();
                    for (var point : points) {
                        var found = candidates.stream().filter(note -> point.matches(note, edit.inBase(note.start(), timeline))
                                && matchesRole(edit, decisions.get(note.id()).role(), edit.role)).map(Note::id).toList();
                        if (found.size() == 1) matching.add(found.getFirst());
                        else outcomes.add(invalidAttack(edit, found));
                    }
                    if (matching.isEmpty()) continue;
                    requested = List.copyOf(matching);
                }
            } else {
                requested = edit.noteIds.stream().filter(id -> !decisions.containsKey(id)
                        || matchesRole(edit, decisions.get(id).role(), edit.role)).toList();
            }
            var affected = new LinkedHashSet<String>();
            var missing = new ArrayList<String>();
            var satisfied = new ArrayList<String>();
            for (String id : requested) {
                var decision = decisions.get(id);
                if (decision == null || deleted.contains(id)) {
                    missing.add(id);
                    continue;
                }
                try {
                    if (deferredDelete && written.containsKey(id)) {
                        missing.add(id);
                        continue;
                    }
                    Role role = deferredDelete ? Role.drop : selectedRoles.getOrDefault(id, edit.role);
                    String at = FILE + "." + edit.id;
                    validateRoleLane(score, decision.note(), role, edit.lane, at);
                    if (edit.laneType != null) {
                        String issue = laneTypeIssue(decision.note(), part(score, decision.note().part()).drums(),
                                edit.laneType, score.inputs().fmOnly());
                        require(issue == null, at + ".laneType", issue);
                    }
                    Role incoming = decision.role();
                    var pin = pins.get(id);
                    boolean alreadySatisfied = role != null && incoming == role
                            && decision.mergedInto() == null && !mergeGroups.containsKey(id)
                            && !ReconstructionLeadContinuity.hasReason(decision, "isolated-harmony-octave-double")
                            && (edit.lane == null || pin != null && edit.lane.equals(pin.lane));
                    if (edit.kind == Kind.setRole && edit.fromRole != null && alreadySatisfied) {
                        satisfied.add(id);
                        resolve(resolutions, edit, written.getOrDefault(id, decision.note()), timeline, incoming, incoming, true);
                        continue;
                    }
                    if (edit.kind == Kind.pinLane && decision.role() == Role.drop && written.get(id) == null)
                        throw new IllegalArgumentException(FILE + "." + edit.id + ".noteIds: no written attack to pin for " + id);
                    boolean merge = decision.mergedInto() != null || mergeGroups.containsKey(id)
                            || ReconstructionLeadContinuity.hasReason(decision, "isolated-harmony-octave-double");
                    if (!deferredDelete && (edit.kind != Kind.unmerge || merge))
                        restore(edit, id, decisions, written, mergeGroups, restored, affected);
                    decision = decisions.get(id);
                    if (role != null) {
                        if (deferredDelete) deleted.add(id);
                        if (role == Role.drop) pins.remove(id);
                        decisions.put(id, new ReconstructionRoles.Decision(decision.note(), role, edit.tag(), null));
                    }
                    if (edit.lane != null || edit.laneType != null)
                        pins.put(id, new Pin(edit.lane, edit.id, order, edit.laneType));
                    resolve(resolutions, edit, written.getOrDefault(id, decision.note()), timeline, incoming,
                            decisions.get(id).role(), alreadySatisfied);
                    affected.add(id);
                } catch (IllegalArgumentException error) {
                    outcomes.add(invalid(edit, id, error));
                }
            }
            if (!affected.isEmpty() && (!scoreEdit || !affected.equals(Set.copyOf(requested))))
                outcomes.add(new Outcome(edit.id, edit.kind, Status.applied, List.copyOf(affected), null, null, null));
            if (!satisfied.isEmpty())
                outcomes.add(new Outcome(edit.id, edit.kind, Status.satisfied, List.copyOf(satisfied), null,
                        "target role is already assigned", null));
            if (!scoreEdit && (requested.isEmpty() || !missing.isEmpty())) outcomes.add(orphan(edit, missing));
        }
        return new ReconstructionRoles.Result(roles.version(), roles.harmonyMaxHz(), decisions.values().stream()
                .sorted(java.util.Comparator.comparing(ReconstructionRoles.Decision::note, NOTE_ORDER)).toList(),
                roles.timeBase(), roles.structureNoteHashes(), pins.isEmpty() ? null : pins);
    }

    private static boolean matchesRole(Intent edit, Role incoming, Role target) {
        return edit.fromRole == null || edit.fromRole == incoming || edit.kind == Kind.setRole && incoming == target;
    }

    private static void resolve(List<Resolution> resolutions, Intent edit, Note note, ReconstructionTimeMap timeline,
                                Role incoming, Role resulting, boolean satisfied) {
        if (resolutions == null) return;
        resolutions.add(new Resolution(edit.id, note.id(), incoming, resulting, satisfied,
                timeline.source(note.start()), note.start()));
    }

    private static void restore(Intent edit, String id, Map<String, ReconstructionRoles.Decision> decisions,
                                Map<String, Note> written, Map<String, List<String>> mergeGroups,
                                Set<String> restored, Set<String> affected) {
        var decision = decisions.get(id);
        String anchor = decision.mergedInto() == null ? id : decision.mergedInto();
        var group = new ArrayList<>(mergeGroups.getOrDefault(anchor, List.of()));
        group.addFirst(anchor);
        for (String member : group) {
            Note note = written.get(member);
            if (note == null || !restored.add(member)) continue;
            var choice = decisions.get(member);
            Role role = choice.role() == Role.drop ? Role.harmony : choice.role();
            if (role != choice.role() || note.start() != choice.note().start() || note.end() != choice.note().end()
                    || choice.mergedInto() != null) {
                decisions.put(member, new ReconstructionRoles.Decision(note, role, edit.tag(), null));
                affected.add(member);
            }
        }
    }

    List<Outcome> allocationOutcomes(ReconstructionAllocation.Result allocation, Map<String, Pin> pins) {
        if (pins == null) return List.of();
        var kinds = new LinkedHashMap<String, Kind>();
        for (var intent : intents) kinds.put(intent.id, intent.kind);
        var outcomes = new ArrayList<Outcome>();
        for (var entry : allocation.notes()) {
            String id = entry.note().id();
            var pin = pins.get(id);
            if (pin != null && (entry.lane() == null || pin.laneType != null) && kinds.containsKey(pin.editId))
                outcomes.add(allocationOutcome(id, entry.lane(), entry.reason(), pin, kinds.get(pin.editId)));
        }
        if (allocation.fastRuns() != null) for (var run : allocation.fastRuns()) {
            var pin = pins.get(run.id());
            if (pin != null && pin.laneType != null && run.lane() != null && kinds.containsKey(pin.editId))
                outcomes.add(allocationOutcome(run.id(), run.lane(), run.reason(), pin, kinds.get(pin.editId)));
        }
        return outcomes;
    }

    private static Outcome allocationOutcome(String id, String lane, String reason, Pin pin, Kind kind) {
        boolean placed = lane != null;
        String detail = placed ? "allocated " + id + " to " + lane + " (" + pin.target() + ")"
                : "could not place " + id + " on " + pin.target() + " (" + reason + ")";
        return new Outcome(pin.editId, kind, placed ? Status.applied : Status.conflict, List.of(id),
                null, detail, null, null, null, pin.laneType, lane);
    }

    private static Note glide(Intent edit, Note from, Note to, String at, ReconstructionTimeMap timeline) {
        require(from.part() == to.part() && from.start() < to.start() && from.pitch() != to.pitch()
                        && to.run() == null && !"external".equals(to.source()),
                at + ".toNoteId", "expected a later single attack of a different pitch in the same part");
        require(ReconstructionOverrides.tick(timeline.project(to.start()))
                        - ReconstructionOverrides.tick(timeline.project(from.start())) >= 4,
                at + ".toNoteId", "glide requires at least four project ticks between attacks");
        double landing = edit.inBase(to.start(), timeline);
        double length = landing - edit.inBase(from.start(), timeline);
        double duration = edit.glideSeconds == null ? Math.min(.12, length) : edit.glideSeconds;
        require(duration <= length + 1e-9, at + ".seconds", "glide must fit between the two attacks");
        double begin = Math.max(edit.inBase(from.start(), timeline), landing - duration);
        int cents = (to.pitch() - from.pitch()) * 100;
        var points = new ArrayList<ReconstructionBends.Point>();
        int segments = edit.shape == Shape.linear ? 1 : 8;
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            double progress = switch (edit.shape) {
                case linear -> t;
                case easeIn -> t * t;
                case easeOut -> t * (2 - t);
                case smooth -> t * t * (3 - 2 * t);
            };
            double time = i == segments ? to.start() : Math.max(from.start(),
                    edit.toSource(begin + t * (landing - begin), timeline));
            points.add(new ReconstructionBends.Point(time, (int) Math.round(progress * cents)));
        }
        var gate = from.withTiming(from.start(), to.start());
        return gate.withBend(bend(edit, gate, at, points, timeline));
    }

    private static ReconstructionBends.Bend bend(Intent edit, Note note, String at,
                                                 List<ReconstructionBends.Point> curve, ReconstructionTimeMap time) {
        require(note.run() == null && !"external".equals(note.source()), at + ".noteId", "bend expects an effective single attack");
        double overshoot = time.project(curve.getLast().time()) - time.project(note.end());
        require(curve.getFirst().time() >= note.start()
                        && overshoot <= 1.0 / S3kRenderer.PRESENTATIONS_PER_SECOND + 1e-9,
                at + ".points", "all point times must be within the note gate [" + note.start() + ", " + note.end() + "]");
        for (var point : curve) {
            int pitch = note.pitch() * 100 + point.cents();
            require(pitch >= 1200 && pitch <= 10600, at + ".points", "curve must stay within S3K pitches 12–106");
        }
        var points = new ArrayList<ReconstructionBends.Point>();
        for (var point : curve) {
            double clamped = Math.min(point.time(), note.end());
            // Several near-gate points can land on the same endpoint. Keep the last value.
            if (!points.isEmpty() && points.getLast().time() == clamped) points.removeLast();
            points.add(new ReconstructionBends.Point(clamped, point.cents()));
        }
        if (points.getFirst().time() > note.start())
            points.addFirst(new ReconstructionBends.Point(note.start(), points.getFirst().cents()));
        require(points.size() <= 512, at + ".points", "use at most 511 points when the curve starts after the note attack");
        int first = points.getFirst().cents();
        int last = points.getLast().cents();
        double delay = points.stream().filter(p -> p.cents() != first).findFirst().map(p -> p.time() - note.start()).orElse(0.0);
        return new ReconstructionBends.Bend(last >= first ? ReconstructionBends.Kind.UP : ReconstructionBends.Kind.DOWN,
                delay, 1, points, edit.tag(), edit.kind == Kind.glide || edit.kind == Kind.mergeNotes && edit.mergeGlide ? true : null);
    }

    private static MidiScore.Part part(Score score, int id) {
        return score.source().parts().stream().filter(part -> part.id() == id).findFirst().orElse(null);
    }

    private static void validateNote(Note note, Score score, MidiScore.Part part, String at) {
        require(note.start() < score.durationUs() / 1e6 && note.end() <= score.durationUs() / 1e6,
                at + ".end", "note must fit within source duration " + score.durationUs() / 1e6);
        require(note.end() > note.start(), at + ".end", "must follow the effective start");
        require(part.drums() || "external".equals(note.source()) || note.pitch() >= 12 && note.pitch() <= 106,
                at + ".pitch", "tonal S3K pitch must be in 12–106");
    }

    private static void validateRoleLane(Score score, Note note, Role role, String lane, String at) {
        boolean drums = part(score, note.part()).drums();
        require(role == null || role == Role.drop || (role == Role.drums) == drums,
                at + ".role", "drums role must match MIDI percussion part");
        require(lane == null || pinCompatible(note, drums, lane, score.inputs().fmOnly()), at + ".lane",
                "lane " + lane + " cannot play this note with the current percussion, pitch or FM-only settings");
    }

    static boolean pinCompatible(Note note, boolean drums, String lane, boolean fmOnly) {
        if (drums) return lane.equals("DAC") || !fmOnly && lane.equals("PSG3");
        return ReconstructionAllocation.FM.contains(lane)
                || !fmOnly && note.pitch() >= 45 && note.run() == null && ReconstructionAllocation.PSG.contains(lane);
    }

    static String laneTypeIssue(Note note, boolean drums, ReconstructionLaneType type, boolean fmOnly) {
        return switch (type) {
            case FM -> drums ? "FM percussion voicing is not supported for MIDI drum parts" : null;
            case DAC -> drums ? null : "DAC requires a MIDI drum part and sampled drum voicing";
            case PSG -> {
                if (drums) yield "PSG tone cannot voice MIDI drums; use NOISE or DAC";
                if (fmOnly) yield "PSG tone is disabled in FM-only mode";
                if (note.pitch() < 45 || note.run() != null) yield "PSG tone requires a single attack at MIDI pitch 45 or higher";
                if (note.bend() != null && note.bend().editReason() != null) yield "authored bends require FM";
                yield null;
            }
            case NOISE -> {
                if (fmOnly) yield "PSG noise is disabled in FM-only mode";
                if (drums) yield null;
                if (note.run() != null || note.bend() != null && note.bend().editReason() != null)
                    yield "pitched noise requires a single attack without an authored bend";
                if (!ReconstructionPitchedNoise.playable(note.pitch()))
                    yield "tone-3 periodic noise cannot tune this pitch within 50 cents";
                yield null;
            }
        };
    }

    private static Outcome orphan(Intent edit, List<String> missing) {
        return new Outcome(edit.id, edit.kind, Status.orphaned, List.of(), missing,
                missing.isEmpty() ? "no matching notes or MIDI part" : "note IDs no longer exist at this stage", null);
    }

    private static Outcome invalidAttack(Intent edit, List<String> matches) {
        return new Outcome(edit.id, edit.kind, Status.invalid, matches, null,
                matches.isEmpty() ? "selector matched no notes" : "selector matched multiple notes: " + matches, null);
    }

    private static Outcome invalid(Intent edit, String id, IllegalArgumentException error) {
        return new Outcome(edit.id, edit.kind, Status.invalid, List.of(id), null, error.getMessage(), null);
    }

    private static void fields(JsonObject json, String at, Set<String> allowed) {
        for (String key : json.keySet()) require(allowed.contains(key), at + "." + key, "unknown field");
    }

    private static JsonArray array(JsonObject json, String key, String at) {
        var value = json.get(key);
        require(value != null && value.isJsonArray(), at + "." + key, "expected an array");
        return value.getAsJsonArray();
    }

    private static String string(JsonObject json, String key, String at) {
        return string(json.get(key), at + "." + key);
    }

    private static String string(JsonElement value, String at) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank(), at, "expected a nonblank string");
        return value.getAsString();
    }

    private static <E extends Enum<E>> E choice(Class<E> type, JsonObject json, String key, String at) {
        String value = string(json, key, at);
        return Arrays.stream(type.getEnumConstants()).filter(option -> option.name().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(at + "." + key + ": expected one of "
                        + Arrays.toString(type.getEnumConstants())));
    }

    private static double number(JsonElement value, String at) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble()), at, "expected a finite number");
        return value.getAsDouble();
    }

    private static Integer integer(JsonObject json, String key, String at, int min, int max) {
        if (!json.has(key)) return null;
        double value = number(json.get(key), at + "." + key);
        require(value == Math.rint(value) && value >= min && value <= max, at + "." + key,
                "expected an integer in " + min + "–" + max);
        return (int) value;
    }

    private static Double time(JsonObject json, String key, String at) {
        return json.has(key) ? time(json.get(key), at + "." + key) : null;
    }

    private static double time(JsonElement value, String at) {
        double time = number(value, at);
        require(time >= 0 && time <= 600, at, "expected source seconds in 0–600");
        return time;
    }
}
