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
import java.util.TreeMap;

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
                           @Nullable String register, @Nullable String basis,
                           @Nullable Map<String, Integer> roles, @Nullable Map<String, Integer> sections,
                           @Nullable Integer singletonAttacks, @Nullable Integer stackedAttacks,
                           @Nullable String attributedStem) {}

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

    public record SourceInstrument(String id, int part, int program, @Nullable String stem) {
        static String id(int part) { return "part-" + part; }
    }

    public record Voice(String id, String name, Kind kind, Evidence evidence, double confidence,
                        List<String> noteIds, @Nullable Assignment assignment, @Nullable List<String> instrumentIds) {}

    public record Identity(String id, String name) {}
    public record Issue(String id, String status, String detail) {}

    public record Result(int version, List<Voice> voices, List<Issue> issues, @Nullable List<SourceInstrument> instruments) {
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

        Map<String, Assignment> assignments(ReconstructionAllocation.Result allocation, Map<String, ReconstructionEdits.Pin> pins) {
            var byNote = byNote();
            var assignments = new java.util.TreeMap<String, Assignment>();
            for (var entry : allocation.notes()) {
                var voice = voiceFor(byNote, entry.note().id());
                if (entry.lane() == null || voice == null || voice.assignment == null) continue;
                boolean compatible = voice.assignment.laneType.accepts(entry);
                // An explicit per-note lane pin supersedes the source voice's chip choice.
                if (!compatible && pins != null && pins.containsKey(entry.note().id())) continue;
                require(compatible, FILE,
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
        final String basis;
        final double confidence;
        final LinkedHashSet<String> notes = new LinkedHashSet<>();
        String attributedStem;
        Assignment assignment;

        Group(String id, String name, Kind kind, String register, String basis, double confidence) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.register = register;
            this.basis = basis;
            this.confidence = confidence;
        }
    }

    private final Path work;
    private final ReconstructionStages.Score score;
    private final ReconstructionTimeMap timeline;
    private final Map<Integer, MidiScore.Part> parts = new HashMap<>();
    private final Map<String, ReconstructionStages.Note> notes = new LinkedHashMap<>();
    private final Map<String, Double> onsets = new HashMap<>();
    private final Map<String, ReconstructionVoiceProposal.Attack> attacks = new HashMap<>();
    private final Map<String, Boolean> stacked = new HashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Group> owners = new HashMap<>();
    private InstrumentCatalogue catalogue;
    private Map<String, List<String>> splitSuccessors;

    private ReconstructionVoicePlan(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws IOException {
        this.work = work;
        this.score = score;
        splitSuccessors = ReconstructionEdits.splitSuccessors(ReconstructionEdits.Journal.read(work)
                .stages().getOrDefault("transcribe", List.of()));
        timeline = ReconstructionTimeMap.of(score);
        for (var part : score.source().parts()) parts.put(part.id(), part);
        var original = new HashMap<String, ReconstructionStages.Note>();
        for (var note : score.notes()) original.put(note.id(), note);
        var sections = new TreeMap<Double, ReconstructionSections.Section>();
        for (var section : ReconstructionSections.load(work, score).sections())
            if (section.level() == ReconstructionSections.Level.section) sections.put(section.sourceStart(), section);

        for (var decision : roles.notes()) {
            var note = decision.note();
            notes.put(note.id(), note);
            var source = original.get(note.id());
            boolean project = roles.timeBase() == ReconstructionTimeMap.TimeBase.project;
            // Prefer original attack times for onset texture, stem evidence and edit selectors.
            double start = source != null ? source.start() : project ? timeline.source(note.start()) : note.start();
            double end = source != null ? source.end() : project ? timeline.source(note.end()) : note.end();
            onsets.put(note.id(), start);
            var entry = sections.floorEntry(start);
            String section = entry != null && start < entry.getValue().sourceEnd() ? entry.getValue().id() : "unsectioned";
            attacks.put(note.id(), new ReconstructionVoiceProposal.Attack(note, decision.role(), start, end, section));
        }
        propose();
    }

    /** Load the current proposal and replay its journal against the saved roles. */
    public static Result load(Path work) throws IOException {
        return review(work, ReconstructionEdits.read(work));
    }

    static Result review(Path work, ReconstructionEdits edits) throws IOException {
        return open(work).apply(combined(work, edits));
    }

    private static List<Edit> combined(Path work, ReconstructionEdits edits) throws IOException {
        var journal = new ArrayList<>(readJournal(work).edits);
        for (var intent : edits.accepted().intents())
            if (intent.kind() == ReconstructionEdits.Kind.voiceEdit)
                journal.add(reviewEdit(intent.json(), ReconstructionEdits.FILE));
        return journal;
    }

    static Edit reviewEdit(JsonObject outer, String at) {
        var value = outer.get("voiceEdit");
        require(value != null && value.isJsonObject(), at + ".voiceEdit", "expected a voice operation");
        var body = value.getAsJsonObject().deepCopy();
        require(!body.has("id") && !body.has("author"), at + ".voiceEdit", "id and author belong to the outer edit");
        body.add("id", outer.get("id"));
        body.addProperty("author", outer.has("author") ? outer.get("author").getAsString() : "agent");
        var wrapped = new JsonObject();
        wrapped.addProperty("version", VERSION);
        var entries = new com.google.gson.JsonArray();
        entries.add(body);
        wrapped.add("edits", entries);
        var edit = ReconstructionArtifacts.decode(Path.of(ReconstructionEdits.FILE), Journal.class, wrapped).edits.getFirst();
        validate(edit, new LinkedHashSet<>());
        return edit;
    }

    static Result generate(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws IOException {
        var result = preview(work, score, roles);
        ReconstructionArtifacts.write(work.resolve(FILE), result);
        return result;
    }

    static Result preview(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles) throws IOException {
        return preview(work, score, roles, ReconstructionEdits.read(work));
    }

    static Result preview(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles,
                          ReconstructionEdits edits) throws IOException {
        return new ReconstructionVoicePlan(work, score, roles).apply(combined(work, edits));
    }

    static Result preview(Path work, ReconstructionStages.Score score, ReconstructionRoles.Result roles,
                          ReconstructionEdits edits, List<ReconstructionEdits.Outcome> scoreEdits) throws IOException {
        var model = new ReconstructionVoicePlan(work, score, roles);
        model.splitSuccessors = ReconstructionEdits.splitSuccessors(scoreEdits);
        return model.apply(combined(work, edits));
    }

    static void reviewOutcomes(ReconstructionEdits edits, Result result, List<ReconstructionEdits.Outcome> outcomes) {
        for (var intent : edits.intents()) {
            if (intent.kind() != ReconstructionEdits.Kind.voiceEdit) continue;
            var edit = reviewEdit(intent.json(), ReconstructionEdits.FILE);
            var issue = result.issues().stream().filter(item -> item.id().equals(edit.id())).findFirst();
            var affected = edit.noteIds() != null ? edit.noteIds() : result.voices().stream()
                    .filter(voice -> voice.id().equals(edit.voiceId())).findFirst()
                    .map(Voice::noteIds).orElse(List.of());
            outcomes.add(new ReconstructionEdits.Outcome(intent.id(), intent.kind(),
                    issue.map(item -> ReconstructionEdits.Status.valueOf(item.status()))
                            .orElse(ReconstructionEdits.Status.applied), affected, null,
                    issue.map(Issue::detail).orElse(null), null));
        }
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
            for (var intent : ReconstructionEdits.read(work).accepted().intents())
                if (intent.kind() == ReconstructionEdits.Kind.voiceEdit)
                    model.replay(reviewEdit(intent.json(), ReconstructionEdits.FILE), ids, issues);
            var result = model.result(issues);
            ReconstructionArtifacts.write(work.resolve(JOURNAL), new Journal(VERSION, accepted));
            return result;
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
        var byPart = new TreeMap<Integer, List<ReconstructionVoiceProposal.Attack>>();
        for (String id : notes.keySet()) {
            var attack = attacks.get(id);
            byPart.computeIfAbsent(attack.part(), ignored -> new ArrayList<>()).add(attack);
        }
        var audio = new ReconstructionVoiceStems(score, byPart);
        for (var entry : byPart.entrySet()) {
            int partId = entry.getKey();
            String id = "part-" + partId;
            var part = parts.get(partId);
            var members = entry.getValue();
            if (part.drums()) {
                for (var attack : members) {
                    String drum = drumClass(attack.note().pitch());
                    String label = "Drum " + drum.replace('-', ' ');
                    double confidence = drum.startsWith("pitch-") ? .65 : .98;
                    var group = groups.computeIfAbsent(id + "-" + drum,
                            key -> new Group(key, label, Kind.drum, null, "General MIDI drum class", confidence));
                    move(attack.note().id(), group);
                }
                continue;
            }
            for (var cluster : ReconstructionVoiceProposal.clusters(members.stream()
                    .filter(attack -> attack.role() != ReconstructionRoles.Role.drop).toList()))
                for (var attack : cluster.notes()) stacked.put(attack.note().id(), cluster.notes().size() > 1);
            String stem = stem(partId);
            for (var proposal : ReconstructionVoiceProposal.identify(members, stem, audio)) {
                String key = proposal.suffix().isEmpty() ? id : id + "-" + proposal.suffix();
                String name = part.name() + (stem == null || proposal.attributedStem() != null ? "" : " (" + stem + ")")
                        + (proposal.label() == null ? "" : " — " + proposal.label());
                var group = new Group(key, name, Kind.tonal, proposal.register(), proposal.basis(), proposal.confidence());
                group.attributedStem = proposal.attributedStem();
                groups.put(key, group);
                for (var attack : proposal.attacks()) move(attack.note().id(), group);
            }
        }
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
            catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
        assignment.validate(catalogue, "assignment.instrument");
    }

    private Group add(String id, String name, Kind kind) {
        require(!id.startsWith("part-"), id, "part- IDs are reserved for automatic voices");
        require(!groups.containsKey(id), id, "voice already exists");
        var group = new Group(id, name, kind, null, null, 1);
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
            var target = ReconstructionEdits.reanchor(edit.noteIds, splitSuccessors);
            var missing = target.stream().filter(id -> !notes.containsKey(id)
                    || source != null && owners.get(id) != source).toList();
            if (!missing.isEmpty()) throw new Orphan("notes missing from selection: " + missing);
            return target;
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
        var instruments = new java.util.TreeMap<Integer, SourceInstrument>();
        for (var group : groups.values()) {
            var members = group.notes.stream().map(notes::get).toList();
            var partIds = members.stream().map(ReconstructionStages.Note::part).distinct().sorted().toList();
            var voiceAttacks = group.notes.stream().map(attacks::get).toList();
            boolean tonal = group.kind == Kind.tonal;
            var stackedCount = tonal ? (int) group.notes.stream().filter(id -> Boolean.TRUE.equals(stacked.get(id))).count() : null;
            var singletonCount = tonal ? (int) group.notes.stream().filter(id -> Boolean.FALSE.equals(stacked.get(id))).count() : null;
            var evidence = new Evidence(partIds, partIds.stream().map(this::stem).filter(java.util.Objects::nonNull).distinct().toList(),
                    partIds.stream().map(id -> parts.get(id).program()).distinct().toList(),
                    members.stream().map(ReconstructionStages.Note::source).distinct().sorted().toList(),
                    group.kind == Kind.drum ? members.stream().map(ReconstructionStages.Note::pitch).distinct().sorted().toList()
                            : List.of(), group.register, group.basis,
                    tonal ? ReconstructionVoiceProposal.counts(voiceAttacks, attack -> attack.role().name()) : null,
                    tonal ? ReconstructionVoiceProposal.counts(voiceAttacks, ReconstructionVoiceProposal.Attack::section) : null,
                    singletonCount, stackedCount, group.attributedStem);
            if (tonal) for (int part : partIds) {
                instruments.putIfAbsent(part, new SourceInstrument(SourceInstrument.id(part), part,
                        parts.get(part).program(), stem(part)));
            }
            voices.add(new Voice(group.id, group.name, group.kind, evidence, group.confidence,
                    List.copyOf(group.notes), group.assignment,
                    tonal ? partIds.stream().map(SourceInstrument::id).toList() : null));
        }
        return new Result(VERSION, List.copyOf(voices), List.copyOf(issues), List.copyOf(instruments.values()));
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
