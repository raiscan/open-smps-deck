package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.*;

final class ReconstructionEdits {
    static final String FILE = "edits.json";
    static final String RESULTS = "edit-results.json";

    enum Kind { insertNote, deleteNote, setNote, setRole, pinLane, unmerge, bend }
    enum Status { applied, orphaned, invalid, conflict }

    record Span(double start, double end) {
        boolean contains(double time) { return start <= time && time < end; }
        Span union(Span other) {
            return other == null ? this : new Span(Math.min(start, other.start), Math.max(end, other.end));
        }
    }

    record Intent(String id, Kind kind, JsonObject json, Integer part, Span span, Integer pitchMin, Integer pitchMax,
                  List<String> noteIds, Double start, Double end, Integer pitch, Integer velocity, Role role,
                  String lane, List<ReconstructionBends.Point> points) {
        String insertedId() { return "edit-" + id; }
        String tag() { return "edit:" + id; }

        Stage firstStage() {
            return switch (kind) {
                case insertNote, deleteNote, setNote, bend -> Stage.TRANSCRIBE;
                case setRole, pinLane, unmerge -> Stage.ROLES;
            };
        }

        boolean matches(Note note, ReconstructionTimeMap timeline) {
            return switch (kind) {
                case insertNote -> insertedId().equals(note.id());
                case setRole, pinLane, unmerge -> span == null ? noteIds.contains(note.id())
                        : part == note.part() && span.contains(timeline.source(note.start()))
                        && (pitchMin == null || note.pitch() >= pitchMin)
                        && (pitchMax == null || note.pitch() <= pitchMax);
                default -> false;
            };
        }

        Span explicitSpan() {
            return span != null ? span : start != null && end != null ? new Span(start, end) : null;
        }
    }

    record Outcome(String id, Kind op, Status status, List<String> noteIds,
                   @Nullable List<String> missingNoteIds, @Nullable String detail, @Nullable Note original) {
        String tag() { return "edit:" + id; }
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

    record Pin(String lane, String editId, int order) {
        String tag() { return "edit:" + editId; }
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
        require(raw.isJsonObject(), FILE, "expected a versioned object");
        JsonObject object = raw.getAsJsonObject();
        fields(object, FILE, Set.of("version", "edits"));
        require(integer(object, "version", FILE, 1, 1) != null, FILE + ".version", "required");
        JsonArray entries = array(object, "edits", FILE);
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

    void write(Path work) throws IOException {
        ReconstructionArtifacts.write(work.resolve(FILE), Map.of("version", VERSION,
                "edits", intents.stream().map(Intent::json).toList()));
    }

    static Intent parse(JsonObject json, String at) {
        String id = string(json, "id", at);
        require(id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"), at + ".id", "expected 1–96 letters, digits, dots, underscores or hyphens");
        string(json, "reason", at);
        Kind kind = choice(Kind.class, json, "op", at);
        var allowed = new LinkedHashSet<>(Set.of("id", "reason", "op"));
        allowed.addAll(switch (kind) {
            case insertNote -> List.of("part", "start", "end", "pitch", "velocity", "lane", "role");
            case deleteNote -> List.of("noteId");
            case setNote -> List.of("noteId", "start", "end", "pitch");
            case setRole -> List.of("noteIds", "span", "part", "pitchMin", "pitchMax", "role", "lane");
            case pinLane -> List.of("noteIds", "lane");
            case unmerge -> List.of("noteIds", "span", "part", "pitchMin", "pitchMax");
            case bend -> List.of("noteId", "points");
        });
        fields(json, at, allowed);

        Integer part = integer(json, "part", at, 0, Integer.MAX_VALUE);
        Integer pitch = integer(json, "pitch", at, 0, 127);
        Integer velocity = integer(json, "velocity", at, 1, 127);
        Integer min = integer(json, "pitchMin", at, 0, 127);
        Integer max = integer(json, "pitchMax", at, 0, 127);
        require(min == null || max == null || min <= max, at + ".pitchMax", "must be at least pitchMin");
        Double start = time(json, "start", at), end = time(json, "end", at);
        require(start == null || end == null || end > start, at + ".end", "must follow start");
        Role role = json.has("role") ? choice(Role.class, json, "role", at) : null;
        String lane = json.has("lane") ? string(json, "lane", at) : null;
        require(lane == null || ReconstructionAllocation.FM.contains(lane) || ReconstructionAllocation.PSG.contains(lane)
                || lane.equals("DAC"), at + ".lane", "expected FM1–FM5, PSG1–PSG3 or DAC");
        require(lane == null || role != Role.drop, at + ".lane", "cannot pin a dropped role");

        Span span = null;
        List<String> ids = List.of();
        switch (kind) {
            case insertNote -> {
                require(part != null, at + ".part", "required");
                require(start != null, at + ".start", "required");
                require(end != null, at + ".end", "required");
                require(pitch != null, at + ".pitch", "required");
            }
            case deleteNote, setNote, bend -> {
                ids = List.of(string(json, "noteId", at));
                require(kind != Kind.setNote || start != null || end != null || pitch != null, at,
                        "setNote requires at least one of start, end or pitch");
            }
            case setRole, pinLane, unmerge -> {
                require(kind != Kind.setRole || role != null, at + ".role", "required");
                require(kind != Kind.pinLane || lane != null, at + ".lane", "required");
                if (json.has("noteIds") || kind == Kind.pinLane) {
                    require(!json.has("span") && part == null && min == null && max == null, at + ".noteIds",
                            "use noteIds or span with part and optional pitch bounds");
                    var values = array(json, "noteIds", at);
                    var unique = new LinkedHashSet<String>();
                    require(!values.isEmpty(), at + ".noteIds", "expected at least one note ID");
                    for (int i = 0; i < values.size(); i++) {
                        String where = at + ".noteIds[" + i + "]";
                        String value = string(values.get(i), where);
                        require(unique.add(value), where, "duplicate note ID " + value);
                    }
                    ids = List.copyOf(unique);
                } else {
                    require(part != null, at + ".part", "required with span");
                    var values = array(json, "span", at);
                    require(values.size() == 2, at + ".span", "expected [start, end] in source seconds");
                    double from = time(values.get(0), at + ".span[0]");
                    double to = time(values.get(1), at + ".span[1]");
                    require(to > from, at + ".span[1]", "must follow span start");
                    span = new Span(from, to);
                }
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
        return new Intent(id, kind, json.deepCopy(), part, span, min, max, ids, start, end, pitch,
                velocity, role, lane, List.copyOf(points));
    }

    Score applyScore(Score score, List<Outcome> outcomes) {
        if (intents.stream().noneMatch(edit -> edit.firstStage() == Stage.TRANSCRIBE)) return score;
        var notes = new LinkedHashMap<String, Note>();
        for (var note : score.notes()) notes.put(note.id(), note);
        var changed = new LinkedHashSet<String>();
        for (Intent edit : intents) {
            if (edit.firstStage() != Stage.TRANSCRIBE) continue;
            String id = edit.kind == Kind.insertNote ? edit.insertedId() : edit.noteIds.getFirst();
            Note before = notes.get(id);
            if (before == null && edit.kind != Kind.insertNote) {
                outcomes.add(orphan(edit, edit.noteIds));
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
                        var note = new Note(id, edit.part, edit.start, edit.end, edit.pitch,
                                edit.velocity == null ? 96 : edit.velocity, "edit", 1);
                        validateNote(note, score, part, at);
                        validateRoleLane(score, note, edit.role, edit.lane, at);
                        notes.put(id, note);
                    }
                    case deleteNote -> notes.remove(id);
                    case setNote -> {
                        require(before.run() == null, at + ".noteId", "setNote expects a single attack");
                        var note = new Note(id, before.part(), edit.start == null ? before.start() : edit.start,
                                edit.end == null ? before.end() : edit.end,
                                edit.pitch == null ? before.pitch() : edit.pitch,
                                before.velocity(), before.source(), before.confidence());
                        validateNote(note, score, part(score, note.part()), at);
                        notes.put(id, note);
                    }
                    case bend -> notes.put(id, before.withBend(bend(edit, before, at)));
                    default -> throw new AssertionError(edit.kind);
                }
                changed.add(id);
                outcomes.add(new Outcome(edit.id, edit.kind, Status.applied, List.of(id), null, null,
                        edit.kind == Kind.deleteNote || edit.kind == Kind.setNote ? before : null));
            } catch (IllegalArgumentException error) {
                outcomes.add(invalid(edit, id, error));
            }
        }
        if (changed.isEmpty()) return score;
        var bursts = score.retriggers() == null ? null : score.retriggers().stream()
                .filter(burst -> !changed.contains(burst.anchor())).toList();
        var voices = score.figureVoices() == null ? null : new LinkedHashMap<>(score.figureVoices());
        if (voices != null) {
            changed.forEach(voices::remove);
            if (score.retriggers() != null) score.retriggers().stream().filter(burst -> changed.contains(burst.anchor()))
                    .forEach(burst -> voices.remove(burst.figure().id()));
        }
        var decisions = score.scoreDecisions() == null ? null : score.scoreDecisions().stream()
                .filter(decision -> notes.containsKey(decision.successorId())).toList();
        return score.withInputs(score.inputs(), score.bars(), notes.values().stream().sorted(NOTE_ORDER).toList(),
                score.tempo(), score.midiBars(), score.beatMap(), voices, bursts, decisions);
    }

    ReconstructionRoles.Result assignRoles(Score score, ReconstructionStructure.Result structure, double rate,
                                           ReconstructionRolePlan plan, ReconstructionLeadContinuity continuity,
                                           List<Outcome> scoreOutcomes, List<Outcome> outcomes) {
        var timeline = ReconstructionTimeMap.of(score);
        var written = new LinkedHashSet<String>();
        var suppressed = new ArrayList<Note>();
        for (Outcome outcome : scoreOutcomes) {
            if (outcome.status != Status.applied) continue;
            written.addAll(outcome.noteIds);
            if (outcome.original != null) suppressed.add(timeline.projectNote(outcome.original));
        }
        var roles = ReconstructionRoles.assign(structure, score.source().parts(), rate, plan,
                score.usesBeatGrid() ? score.beatMap() : null, continuity, score.inputs().leadPart(), written,
                note -> !written.contains(note.id()) && ReconstructionStructure.suppressed(suppressed, note, note.start(), .035));
        return applyRoles(score, structure, roles, scoreOutcomes, outcomes);
    }

    ReconstructionRoles.Result applyRoles(Score score, ReconstructionStructure.Result structure,
                                          ReconstructionRoles.Result roles, List<Outcome> scoreOutcomes,
                                          List<Outcome> outcomes) {
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
        int order = 0;
        for (Intent edit : intents) {
            order++;
            if (edit.kind == Kind.deleteNote) continue;
            var applied = scoreOutcomes.stream().filter(outcome -> outcome.id.equals(edit.id)
                    && outcome.status == Status.applied).findFirst().orElse(null);
            boolean scoreEdit = edit.firstStage() == Stage.TRANSCRIBE;
            if (scoreEdit && applied == null && edit.kind != Kind.insertNote) continue;
            // Insert metadata can also be applied when resuming at roles with an existing edited score.
            if (scoreEdit && edit.kind == Kind.insertNote && !decisions.containsKey(edit.insertedId())) continue;
            List<String> requested = scoreEdit
                    ? edit.kind == Kind.insertNote ? List.of(edit.insertedId()) : applied.noteIds
                    : edit.span == null ? edit.noteIds
                    : decisions.values().stream().map(decision -> written.getOrDefault(decision.note().id(), decision.note()))
                            .filter(note -> edit.matches(note, timeline)).map(Note::id).toList();
            var affected = new LinkedHashSet<String>();
            var missing = new ArrayList<String>();
            for (String id : requested) {
                var decision = decisions.get(id);
                if (decision == null) {
                    missing.add(id);
                    continue;
                }
                try {
                    validateRoleLane(score, decision.note(), edit.role, edit.lane, FILE + "." + edit.id);
                    if (edit.kind == Kind.pinLane && decision.role() == Role.drop && written.get(id) == null)
                        throw new IllegalArgumentException(FILE + "." + edit.id + ".noteIds: no written attack to pin for " + id);
                    boolean merge = decision.mergedInto() != null || mergeGroups.containsKey(id)
                            || ReconstructionLeadContinuity.hasReason(decision, "isolated-harmony-octave-double");
                    if (edit.kind != Kind.unmerge || merge)
                        restore(edit, id, decisions, written, mergeGroups, restored, affected);
                    decision = decisions.get(id);
                    if (edit.role != null) {
                        if (edit.role == Role.drop) pins.remove(id);
                        decisions.put(id, new ReconstructionRoles.Decision(decision.note(), edit.role, edit.tag(), null));
                    }
                    if (edit.lane != null) pins.put(id, new Pin(edit.lane, edit.id, order));
                    affected.add(id);
                } catch (IllegalArgumentException error) {
                    outcomes.add(invalid(edit, id, error));
                }
            }
            if (!affected.isEmpty() && (!scoreEdit || !affected.equals(Set.copyOf(requested))))
                outcomes.add(new Outcome(edit.id, edit.kind, Status.applied, List.copyOf(affected), null, null, null));
            if (!scoreEdit && (requested.isEmpty() || !missing.isEmpty())) outcomes.add(orphan(edit, missing));
        }
        return new ReconstructionRoles.Result(roles.version(), roles.harmonyMaxHz(), decisions.values().stream()
                .sorted(java.util.Comparator.comparing(ReconstructionRoles.Decision::note, NOTE_ORDER)).toList(),
                roles.timeBase(), roles.structureNoteHashes(), pins.isEmpty() ? null : pins);
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
        var outcomes = new ArrayList<Outcome>();
        for (var entry : allocation.notes()) {
            String id = entry.note().id();
            Pin pin = pins.get(id);
            if (pin == null || entry.lane() != null) continue;
            Intent intent = intents.stream().filter(edit -> edit.id.equals(pin.editId)).findFirst().orElse(null);
            if (intent != null) {
                String detail = "could not place " + id + " on " + pin.lane + " (" + entry.reason() + ")";
                outcomes.add(new Outcome(intent.id, intent.kind, Status.conflict, List.of(id), null, detail, null));
            }
        }
        return outcomes;
    }

    private static ReconstructionBends.Bend bend(Intent edit, Note note, String at) {
        require(note.run() == null && !"external".equals(note.source()), at + ".noteId", "bend expects an effective single attack");
        require(edit.points.getFirst().time() >= note.start() && edit.points.getLast().time() <= note.end(),
                at + ".points", "all point times must be within the note gate [" + note.start() + ", " + note.end() + "]");
        for (var point : edit.points) {
            int pitch = note.pitch() * 100 + point.cents();
            require(pitch >= 1200 && pitch <= 10600, at + ".points", "curve must stay within S3K pitches 12–106");
        }
        var points = new ArrayList<>(edit.points);
        if (points.getFirst().time() > note.start())
            points.addFirst(new ReconstructionBends.Point(note.start(), points.getFirst().cents()));
        require(points.size() <= 512, at + ".points", "use at most 511 points when the curve starts after the note attack");
        int first = points.getFirst().cents();
        int last = points.getLast().cents();
        double delay = points.stream().filter(p -> p.cents() != first).findFirst().map(p -> p.time() - note.start()).orElse(0.0);
        return new ReconstructionBends.Bend(last >= first ? ReconstructionBends.Kind.UP : ReconstructionBends.Kind.DOWN,
                delay, 1, points, edit.tag());
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

    private static Outcome orphan(Intent edit, List<String> missing) {
        return new Outcome(edit.id, edit.kind, Status.orphaned, List.of(), missing,
                missing.isEmpty() ? "no matching notes or MIDI part" : "note IDs no longer exist at this stage", null);
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
