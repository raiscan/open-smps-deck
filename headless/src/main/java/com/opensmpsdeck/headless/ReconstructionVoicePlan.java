package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Source instrument identities and their ordered, authored corrections. */
public final class ReconstructionVoicePlan {
    public static final String FILE = "voices.json";
    public static final String JOURNAL = "voice-edits.json";
    public static final String LOCK = ".voice-edits.lock";
    private static final List<String> ACTIONS = List.of("create", "rename", "merge", "split", "move", "assign");

    public enum Kind { tonal, drum }

    public record Evidence(List<Integer> parts, List<String> stems, List<Integer> programs,
                           List<String> noteSources, List<Integer> drumPitches,
                           @Nullable String register) {}

    /** Instrument is a catalogue variant ID, or a named noise preset for NOISE. */
    public record Assignment(ReconstructionLaneType laneType, String instrument) {
        void validate(InstrumentCatalogue catalogue, String at) {
            if (laneType == ReconstructionLaneType.NOISE) {
                require(List.of("fixed", "closed-hat", "cymbal").contains(instrument), at,
                        "expected fixed, closed-hat or cymbal");
                return;
            }
            ReconstructionVoicing.kind(catalogue, instrument, switch (laneType) {
                case FM -> InstrumentCatalogue.Kind.FM_VOICE;
                case PSG -> InstrumentCatalogue.Kind.PSG_VOLUME;
                case DAC -> InstrumentCatalogue.Kind.DAC_SAMPLE;
                case NOISE -> throw new AssertionError(laneType);
            }, at);
        }
    }

    public enum Clock { source, project }

    public record Voice(String id, String name, Kind kind, Evidence evidence, double confidence,
                        List<String> noteIds, @Nullable Assignment assignment) {}

    public record Identity(String id, String name) {}
    public record Issue(String id, String status, String detail) {}

    public record Result(int version, List<Voice> voices, List<Issue> issues) {
        Map<String, Voice> byNote() {
            var index = new HashMap<String, Voice>();
            for (var voice : voices) for (String id : voice.noteIds) index.put(id, voice);
            return index;
        }

        Map<String, ReconstructionLaneType> laneTypes() {
            var types = new HashMap<String, ReconstructionLaneType>();
            for (var voice : voices) if (voice.assignment != null)
                for (String note : voice.noteIds) types.put(note, voice.assignment.laneType);
            return types;
        }

        Map<String, Assignment> assignments(ReconstructionAllocation.Result allocation) {
            var byNote = byNote();
            var assignments = new java.util.TreeMap<String, Assignment>();
            for (var entry : allocation.notes()) {
                var voice = voiceFor(byNote, entry.note().id());
                if (entry.lane() == null || voice == null || voice.assignment == null) continue;
                require(voice.assignment.laneType.accepts(entry.lane(), voice.kind == Kind.drum), FILE,
                        "note " + entry.note().id() + " needs " + voice.assignment.laneType
                                + "; resume from allocate to apply the voice assignment");
                assignments.put(entry.note().id(), voice.assignment);
            }
            return assignments;
        }
    }

    public record Selector(double at, int pitch, int part) {}

    /** Fields are operation-specific. Spans use half-open onset bounds. */
    public record Edit(String id, String op, String author, String voiceId,
                       @Nullable String name, @Nullable String targetId, @Nullable Kind kind,
                       @Nullable List<String> noteIds, @Nullable List<Selector> selectors,
                       @Nullable List<Double> span, @Nullable Integer part, @Nullable Integer pitchMin,
                       @Nullable Integer pitchMax, @Nullable Clock timeBase,
                       @Nullable Assignment assignment, @Nullable Boolean clear) {}

    private record Journal(int version, List<Edit> edits) {}

    private static final class Group {
        final String id;
        String name;
        final Kind kind;
        final String register;
        final double confidence;
        final LinkedHashSet<String> notes = new LinkedHashSet<>();
        Assignment assignment;

        Group(String id, String name, Kind kind, String register, double confidence) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.register = register;
            this.confidence = confidence;
        }
    }

    private final Path work;
    private final ReconstructionStages.Score score;
    private final ReconstructionTimeMap timeline;
    private final Map<Integer, MidiScore.Part> parts = new HashMap<>();
    private final Map<String, ReconstructionStages.Note> notes = new LinkedHashMap<>();
    private final Map<String, Double> onsets = new HashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Group> owners = new HashMap<>();
    private InstrumentCatalogue catalogue;

    private ReconstructionVoicePlan(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) {
        this.work = work;
        this.score = score;
        timeline = ReconstructionTimeMap.of(score);
        for (var part : score.source().parts()) parts.put(part.id(), part);
        for (var decision : roles.notes()) notes.put(decision.note().id(), decision.note());
        // Use score attacks where available so a role correction does not move selector anchors.
        for (var note : notes.values()) onsets.put(note.id(), roles.timeBase() == ReconstructionTimeMap.TimeBase.project
                ? timeline.source(note.start()) : note.start());
        for (var note : score.notes()) if (onsets.containsKey(note.id())) onsets.put(note.id(), note.start());
        propose();
    }

    /** Load the current proposal and replay its journal against the saved roles. */
    public static Result load(Path work) throws IOException {
        return open(work).apply(readJournal(work).edits);
    }

    static Result generate(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws IOException {
        var result = preview(work, score, roles);
        ReconstructionArtifacts.write(work.resolve(FILE), result);
        return result;
    }

    static Result preview(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws IOException {
        return new ReconstructionVoicePlan(work, score, roles).apply(readJournal(work).edits);
    }

    static Result saved(Path work) throws IOException {
        Path file = work.resolve(FILE);
        return Files.exists(file) ? ReconstructionArtifacts.read(file, Result.class) : null;
    }

    private static ReconstructionVoicePlan open(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var roles = ReconstructionArtifacts.read(work.resolve("roles.json"), ReconstructionRoles.Result.class);
        ReconstructionChecks.roles(roles, score);
        return new ReconstructionVoicePlan(work, score, roles);
    }

    private static Journal readJournal(Path work) throws IOException {
        var file = work.resolve(JOURNAL);
        return Files.exists(file) ? ReconstructionArtifacts.read(file, Journal.class) : new Journal(VERSION, List.of());
    }

    /** Append a batch atomically. Regenerate downstream stages with run --from voices. */
    public static synchronized Result edit(Path work, List<Edit> edits) throws IOException {
        require(!edits.isEmpty(), JOURNAL, "expected at least one edit");
        try (var channel = FileChannel.open(work.resolve(LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            var model = open(work);
            var prior = readJournal(work).edits;
            var issues = new ArrayList<Issue>();
            var ids = new LinkedHashSet<String>();
            for (var edit : prior) model.replay(edit, ids, issues);
            for (var edit : edits) {
                validate(edit, ids);
                model.apply(edit);
            }
            var accepted = new ArrayList<>(prior);
            accepted.addAll(edits);
            ReconstructionArtifacts.write(work.resolve(JOURNAL), new Journal(VERSION, accepted));
            return model.result(issues);
        }
    }

    public static Result run(Path work, String action, JsonElement data) throws IOException {
        if (action.equals("list")) {
            require(data == null, action, "takes no payload");
            return load(work);
        }
        require(ACTIONS.contains(action), "action", "unknown voice operation");
        require(data != null && (data.isJsonObject() || data.isJsonArray()), action, "expected an object or array");
        var entries = new com.google.gson.JsonArray();
        var batch = data.isJsonArray() ? data.getAsJsonArray().asList() : List.of(data);
        for (var entry : batch) {
            require(entry.isJsonObject() && !entry.getAsJsonObject().has("op"), action, "expected an edit without op");
            var copy = entry.getAsJsonObject().deepCopy();
            copy.addProperty("op", action);
            entries.add(copy);
        }
        var wrapped = new JsonObject();
        wrapped.addProperty("version", VERSION);
        wrapped.add("edits", entries);
        return edit(work, ReconstructionArtifacts.decode(work.resolve(JOURNAL), Journal.class, wrapped).edits);
    }

    static void cli(String[] args, PrintStream out) throws IOException {
        require(args.length >= 1 && args.length <= 3, "voices", "expected WORK_DIR [OP [JSON]]");
        var result = run(Path.of(args[0]), args.length >= 2 ? args[1] : "list",
                args.length == 3 ? JsonParser.parseString(args[2]) : null);
        out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private void propose() {
        var byPart = new java.util.TreeMap<Integer, List<ReconstructionStages.Note>>();
        for (var note : notes.values()) byPart.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
        for (var entry : byPart.entrySet()) {
            int partId = entry.getKey();
            var part = parts.get(partId);
            String stem = stem(partId);
            boolean drums = part.drums();
            int split = drums ? -1 : registerSplit(entry.getValue());
            for (var note : entry.getValue()) {
                String drum = drums ? drumClass(note.pitch()) : null;
                String register = split < 0 ? null : note.pitch() < split ? "lower" : "upper";
                String id = "part-" + partId + (drums ? "-" + drum : "upper".equals(register) ? "-upper" : "");
                String label = drums ? "Drum " + drum.replace('-', ' ')
                        : part.name() + (stem == null ? "" : " (" + stem + ")")
                        + (register == null ? "" : " · " + register);
                double confidence = drums ? (drum.startsWith("pitch-") ? .65 : .98)
                        : register != null ? .65 : stem == null ? .75 : .9;
                var group = groups.computeIfAbsent(id, key -> new Group(key, label, drums ? Kind.drum : Kind.tonal,
                        register, confidence));
                move(note.id(), group);
            }
        }
    }

    // A wide gap alone can describe a melody's register change. Require repeated
    // simultaneous attacks on both sides before proposing two instruments.
    private static int registerSplit(List<ReconstructionStages.Note> notes) {
        var ordered = notes.stream().sorted(java.util.Comparator.comparingInt(ReconstructionStages.Note::pitch)).toList();
        int gap = 11, boundary = -1;
        for (int i = 8; i <= ordered.size() - 8; i++) {
            int distance = ordered.get(i).pitch() - ordered.get(i - 1).pitch();
            if (distance <= gap) continue;
            int pitch = ordered.get(i).pitch();
            var lower = new java.util.HashSet<Integer>();
            var upper = new java.util.HashSet<Integer>();
            for (var note : ordered)
                (note.pitch() < pitch ? lower : upper).add(ReconstructionOverrides.tick(note.start()));
            lower.retainAll(upper);
            if (lower.size() >= 3) {
                boundary = pitch;
                gap = distance;
            }
        }
        return boundary;
    }

    private String stem(int part) {
        String path = score.inputs() == null ? null : score.inputs().stems().get(part);
        return path == null ? null : Path.of(path).getFileName().toString();
    }

    private static String drumClass(int pitch) {
        return switch (pitch) {
            case 35, 36 -> "kick";
            case 38, 40 -> "snare";
            case 37 -> "side-stick";
            case 39 -> "clap";
            case 42 -> "closed-hat";
            case 44 -> "pedal-hat";
            case 46 -> "open-hat";
            case 49, 52, 55, 57 -> "crash";
            case 51, 53, 59 -> "ride";
            case 41, 43, 45, 47, 48, 50 -> "tom";
            case 54 -> "tambourine";
            case 56 -> "cowbell";
            case 58 -> "vibraslap";
            default -> "pitch-" + pitch;
        };
    }

    private Result apply(List<Edit> edits) throws IOException {
        var issues = new ArrayList<Issue>();
        var ids = new LinkedHashSet<String>();
        for (var edit : edits) replay(edit, ids, issues);
        return result(issues);
    }

    private void replay(Edit edit, LinkedHashSet<String> ids, List<Issue> issues) throws IOException {
        try {
            validate(edit, ids);
            apply(edit);
        } catch (Orphan orphan) {
            issues.add(new Issue(edit.id, "orphaned", orphan.getMessage()));
        } catch (IllegalArgumentException invalid) {
            issues.add(new Issue(edit.id, "invalid", invalid.getMessage()));
        }
    }

    private static void validate(Edit edit, LinkedHashSet<String> ids) {
        require(edit.id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}") && ids.add(edit.id),
                JOURNAL, "expected a unique edit ID of 1–96 letters, digits, dots, underscores or hyphens");
        String at = JOURNAL + "." + edit.id;
        require(ACTIONS.contains(edit.op), at, "unknown operation");
        require(List.of("human", "agent").contains(edit.author), at, "author must be human or agent");
        require(edit.voiceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"), at, "invalid voiceId");
        boolean needsName = List.of("create", "rename", "split").contains(edit.op);
        boolean needsTarget = List.of("merge", "split").contains(edit.op);
        boolean selects = List.of("move", "split").contains(edit.op);
        require(needsName ? edit.name != null && !edit.name.isBlank() : edit.name == null, at, "unexpected or missing name");
        require(needsTarget ? edit.targetId != null && edit.targetId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                && !edit.targetId.equals(edit.voiceId) : edit.targetId == null, at, "unexpected or invalid targetId");
        require(edit.op.equals("create") == (edit.kind != null), at, "kind is required for create");
        int selectors = (edit.noteIds == null ? 0 : 1) + (edit.selectors == null ? 0 : 1) + (edit.span == null ? 0 : 1);
        require(selectors == (selects ? 1 : 0), at, "move and split take one of noteIds, selectors or span");
        require(edit.noteIds == null || !edit.noteIds.isEmpty() && !edit.noteIds.contains("")
                && new LinkedHashSet<>(edit.noteIds).size() == edit.noteIds.size(), at, "expected distinct noteIds");
        require(edit.selectors == null || !edit.selectors.isEmpty() && edit.selectors.stream()
                .allMatch(p -> p.at >= 0 && p.pitch >= 0 && p.pitch <= 127 && p.part >= 0), at, "invalid selectors");
        require(edit.span == null ? edit.part == null && edit.pitchMin == null && edit.pitchMax == null
                : edit.span.size() == 2 && edit.span.getFirst() >= 0 && edit.span.getLast() > edit.span.getFirst()
                && (edit.part == null || edit.part >= 0)
                && (edit.pitchMin == null || edit.pitchMin >= 0 && edit.pitchMin <= 127)
                && (edit.pitchMax == null || edit.pitchMax >= 0 && edit.pitchMax <= 127)
                && (edit.pitchMin == null || edit.pitchMax == null || edit.pitchMin <= edit.pitchMax),
                at, "invalid span or filters");
        require(edit.timeBase == null || edit.span != null || edit.selectors != null, at, "timeBase requires selectors or span");
        require(edit.op.equals("assign") ? (edit.assignment != null) != Boolean.TRUE.equals(edit.clear)
                : edit.assignment == null && edit.clear == null, at, "assign takes assignment or clear:true");
    }

    private void apply(Edit edit) throws IOException {
        if (edit.op.equals("create")) {
            add(edit.voiceId, edit.name, edit.kind);
            return;
        }
        var group = group(edit.voiceId);
        switch (edit.op) {
            case "rename" -> group.name = edit.name;
            case "assign" -> {
                if (edit.assignment != null) validateAssignment(group.kind, edit.assignment);
                group.assignment = edit.assignment;
            }
            case "merge" -> {
                var target = group(edit.targetId);
                require(group.kind == target.kind, edit.id, "cannot merge tonal and drum voices");
                require(group.assignment == null || target.assignment == null || group.assignment.equals(target.assignment),
                        edit.id, "clear one assignment before merging incompatible instruments");
                if (target.assignment == null) target.assignment = group.assignment;
                for (String id : List.copyOf(group.notes)) move(id, target);
                groups.remove(group.id);
            }
            case "split", "move" -> {
                boolean split = edit.op.equals("split");
                var selection = select(edit, split ? group : null);
                for (String id : selection) require(owners.get(id).kind == group.kind, edit.id,
                        "note " + id + " belongs to a different kind");
                var target = split ? add(edit.targetId, edit.name, group.kind) : group;
                if (split) target.assignment = group.assignment;
                for (String id : selection) move(id, target);
            }
            default -> throw new AssertionError(edit.op);
        }
    }

    private void validateAssignment(Kind kind, Assignment assignment) throws IOException {
        var type = assignment.laneType;
        require(type.isDrum() == (kind == Kind.drum), "assignment", "lane type must match voice kind");
        require(!score.inputs().fmOnly() || type != ReconstructionLaneType.PSG && type != ReconstructionLaneType.NOISE,
                "assignment", "PSG and NOISE require the PSG chip");
        if (type != ReconstructionLaneType.NOISE && catalogue == null)
            catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()));
        assignment.validate(catalogue, "assignment.instrument");
    }

    private Group add(String id, String name, Kind kind) {
        require(!id.startsWith("part-"), id, "part- IDs are reserved for automatic voices");
        require(!groups.containsKey(id), id, "voice already exists");
        var group = new Group(id, name, kind, null, 1);
        groups.put(id, group);
        return group;
    }

    private Group group(String id) {
        var group = groups.get(id);
        if (group == null) throw new Orphan("voice " + id + " is missing");
        return group;
    }

    private void move(String id, Group target) {
        var source = owners.put(id, target);
        if (source != null) source.notes.remove(id);
        target.notes.add(id);
    }

    private List<String> select(Edit edit, Group source) {
        if (edit.noteIds != null) {
            var missing = edit.noteIds.stream().filter(id -> !notes.containsKey(id)
                    || source != null && owners.get(id) != source).toList();
            if (!missing.isEmpty()) throw new Orphan("notes missing from selection: " + missing);
            return edit.noteIds;
        }
        var candidates = source == null ? notes.values() : source.notes.stream().map(notes::get).toList();
        var selected = new LinkedHashSet<String>();
        if (edit.selectors != null) for (var point : edit.selectors) {
            var match = new ReconstructionEdits.Attack(point.at, point.pitch, point.part);
            var hits = candidates.stream().filter(note -> match.matches(note, onset(note.id(), edit.timeBase))).toList();
            if (hits.isEmpty()) throw new Orphan("no attack matches " + point);
            require(hits.size() == 1, edit.id, "ambiguous attack selector " + point);
            selected.add(hits.getFirst().id());
        } else {
            for (var note : candidates) {
                double at = onset(note.id(), edit.timeBase);
                if (at >= edit.span.getFirst() && at < edit.span.getLast()
                        && (edit.part == null || edit.part == note.part())
                        && (edit.pitchMin == null || edit.pitchMin <= note.pitch())
                        && (edit.pitchMax == null || edit.pitchMax >= note.pitch())) selected.add(note.id());
            }
        }
        if (selected.isEmpty()) throw new Orphan("no notes match the selection");
        return List.copyOf(selected);
    }

    private double onset(String id, Clock clock) {
        double source = onsets.get(id);
        return clock == Clock.project ? timeline.project(source) : source;
    }

    private Result result(List<Issue> issues) {
        var voices = new ArrayList<Voice>();
        for (var group : groups.values()) {
            var members = group.notes.stream().map(notes::get).toList();
            var partIds = members.stream().map(ReconstructionStages.Note::part).distinct().sorted().toList();
            var evidence = new Evidence(partIds, partIds.stream().map(this::stem).filter(java.util.Objects::nonNull).distinct().toList(),
                    partIds.stream().map(id -> parts.get(id).program()).distinct().toList(),
                    members.stream().map(ReconstructionStages.Note::source).distinct().sorted().toList(),
                    group.kind == Kind.drum ? members.stream().map(ReconstructionStages.Note::pitch).distinct().sorted().toList()
                            : List.of(), group.register);
            voices.add(new Voice(group.id, group.name, group.kind, evidence, group.confidence,
                    List.copyOf(group.notes), group.assignment));
        }
        return new Result(VERSION, List.copyOf(voices), List.copyOf(issues));
    }

    static Voice voiceFor(Map<String, Voice> byNote, String noteId) {
        var voice = byNote.get(noteId);
        if (voice != null) return voice;
        int step = noteId.lastIndexOf("-step-");
        return step < 0 ? null : byNote.get(noteId.substring(0, step));
    }

    private static final class Orphan extends IllegalArgumentException {
        Orphan(String message) { super(message); }
    }
}
