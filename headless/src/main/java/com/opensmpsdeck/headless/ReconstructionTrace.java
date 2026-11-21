package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;

public final class ReconstructionTrace {
    public static final List<String> FILES = List.of("score.json", "structure.json", "roles.json",
            "allocation.json", "voicing.json", "report.json", ReconstructionEdits.RESULTS);

    enum Status { kept, moved, shortened, merged, dropped }

    record Span(Double start, Double end) {
        Span {
            if (start != null && (!Double.isFinite(start) || start < 0)
                    || end != null && (!Double.isFinite(end) || end < 0)
                    || start != null && end != null && start >= end)
                throw new IllegalArgumentException("Expected a finite, nonnegative time range with start < end");
        }

        boolean overlaps(Timing time) {
            if (time == null) return false;
            return (start == null || time.sourceEnd > start || time.sourceStart >= start)
                    && (end == null || time.sourceStart < end);
        }
    }

    record Timing(double sourceStart, double sourceEnd, double sourceGate,
                  double projectStart, double projectEnd, double projectGate,
                  long startTick, long endTick, long gateTicks) {}
    record NoteAt(Note note, Timing timing) {}
    record Origin(NoteAt note, String provenance, ExternalTranscribers.Evidence transcriber,
                  String parentNoteId, List<ReconstructionPitchRefinement.Change> pitchRefinement) {}
    record Structure(NoteAt effective, List<ReconstructionStructure.Correction> corrections) {}
    record Roles(NoteAt effective, ReconstructionRoles.Role role, String reason, String mergedInto, Double preLegatoEnd, List<String> mergedFrom) {}
    record Allocation(NoteAt effective, ReconstructionRoles.Role role, List<String> expectedLanes, String lane,
                      Timing placement, ReconstructionAllocation.Outcome outcome, String reason, String mergedInto,
                      String competitorNoteId, List<String> blockingNoteIds, String relocatedFrom,
                      List<ReconstructionAllocation.RunPlacement> figures) {}
    record Voiced(String id, String lane, int pitch, Timing gate, ReconstructionBendEncoding.Applied bend,
                  String evidence) {}
    record Row(String id, int part, String partName, int pitch, Timing timing, Origin source, NoteAt score,
               ReconstructionStages.ScoreDecision scoreDecision, Structure structure, Roles roles,
               Allocation allocation, List<Voiced> voiced,
               Status status, String stage, String reason, List<String> editReasons, String fateDetail) {}
    record Summary(int part, int pitch, Timing sourceTiming, Status status, String stage, String reason,
                   ReconstructionRoles.Role role, String roleReason, String lane, Timing placement,
                   String mergedInto, String competitorNoteId, List<String> blockingNoteIds, List<Voiced> voiced) {}
    record Change(String id, Boolean outsideSpan, List<String> changedStages, Summary old,
                  @com.google.gson.annotations.SerializedName("new") Summary updated) {}
    private record PinLane(String id, String reason, String op, List<String> noteIds, String lane) {}
    private record LanePermutation(Map<String, String> lanes, int noteCount, double startSeconds, double endSeconds,
                                   List<String> firstNoteIds, List<PinLane> pins) {}

    private static final class PermutationGroup {
        private final Map<String, String> lanes = new java.util.TreeMap<>();
        private final Map<String, String> firstNotes = new java.util.TreeMap<>();
        private double start = Double.POSITIVE_INFINITY;
        private double end;
        private int notes;

        boolean touches(String from, String to) {
            return contains(from) || contains(to);
        }

        private boolean contains(String lane) {
            return lane != null && (lanes.containsKey(lane) || lanes.containsValue(lane));
        }

        boolean accepts(String from, String to) {
            return (lanes.containsKey(from) ? lanes.get(from).equals(to) : !lanes.containsValue(to));
        }

        void add(Row row, String from, String to) {
            lanes.put(from, to);
            firstNotes.putIfAbsent(from, row.id);
            start = Math.min(start, row.allocation.placement.sourceStart);
            end = Math.max(end, row.allocation.placement.sourceEnd);
            notes++;
        }

        void merge(PermutationGroup other) {
            lanes.putAll(other.lanes);
            firstNotes.putAll(other.firstNotes);
            start = Math.min(start, other.start);
            end = Math.max(end, other.end);
            notes += other.notes;
        }

        LanePermutation finish(int index) {
            var pins = firstNotes.entrySet().stream().map(entry -> new PinLane(
                    "resync-" + index + "-" + entry.getKey(), "Restore baseline lane at first divergence",
                    "pinLane", List.of(entry.getValue()), entry.getKey())).toList();
            return new LanePermutation(lanes, notes, start, end, List.copyOf(firstNotes.values()), pins);
        }
    }

    private record Fate(Status status, String stage, String reason) {}
    private record Raw(Note note, ExternalTranscribers.Evidence external, String decision) {}
    private record PartOnset(int part, long startUs) {}
    private record Refinement(MidiScore.Note original, String parentId, List<ReconstructionPitchRefinement.Change> changes) {}
    private record LaneEvidence(Map<String, java.util.NavigableMap<Integer, ReconstructionAllocation.Entry>> lanes) {
        static LaneEvidence of(Iterable<ReconstructionAllocation.Entry> notes) {
            var lanes = new HashMap<String, java.util.NavigableMap<Integer, ReconstructionAllocation.Entry>>();
            for (var note : notes) if (note.lane() != null)
                lanes.computeIfAbsent(note.lane(), ignored -> new java.util.TreeMap<>()).put(note.startTick(), note);
            return new LaneEvidence(lanes);
        }

        String next(String lane, int tick) {
            var notes = lanes.get(lane);
            var next = notes == null ? null : notes.get(tick);
            return next == null ? null : next.note().id();
        }

        List<String> blockers(ReconstructionAllocation.Entry entry, List<String> expected, int tick) {
            String reason = entry.reason();
            if (!List.of("simultaneous-dac-hit", "protect-dac-tail", "melody-lane-busy", "bass-lane-busy",
                    "psg-noise-lane-busy", "fm-lanes-busy", "fm-and-psg-lanes-busy").contains(reason)) return List.of();
            var considered = new ArrayList<>(expected);
            if ("fm-and-psg-lanes-busy".equals(reason)) considered.addAll(ReconstructionAllocation.PSG);
            var blocking = new ArrayList<String>();
            for (String lane : considered) {
                var note = lanes.containsKey(lane) ? lanes.get(lane).floorEntry(tick) : null;
                if (note == null) continue;
                var occupant = note.getValue();
                if (occupant.endTick() > tick || "protect-dac-tail".equals(reason) && tick - occupant.startTick() < 5)
                    blocking.add(occupant.note().id());
            }
            return List.copyOf(blocking);
        }
    }

    private static final com.google.gson.Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ReconstructionTrace() {}

    public static JsonObject trace(Path work, String... options) throws IOException {
        var flags = flags(options, "--start", "--end", "--part", "--pitch", "--pitch-min", "--pitch-max", "--lane", "--status");
        var span = new Span(number(flags, "--start"), number(flags, "--end"));
        Integer part = integer(flags, "--part");
        Integer pitch = integer(flags, "--pitch"), min = integer(flags, "--pitch-min"), max = integer(flags, "--pitch-max");
        if (pitch != null && (min != null || max != null))
            throw new IllegalArgumentException("--pitch cannot be combined with --pitch-min or --pitch-max");
        if (pitch != null) min = max = pitch;
        if (part != null && part < 0 || min != null && (min < 0 || min > 127)
                || max != null && (max < 0 || max > 127) || min != null && max != null && min > max)
            throw new IllegalArgumentException("Expected nonnegative part IDs and an ordered MIDI pitch range (0–127)");
        String lane = flags.get("--lane");
        if (lane != null && !ReconstructionAllocation.FM.contains(lane) && !ReconstructionAllocation.PSG.contains(lane)
                && !lane.equals("DAC"))
            throw new IllegalArgumentException("--lane expects FM1–FM5, PSG1–PSG3, or DAC");
        Status status = flags.containsKey("--status") ? Status.valueOf(flags.get("--status")) : null;
        var notes = new ArrayList<Row>();
        for (var row : read(work).values()) {
            if (!overlaps(span, row) || part != null && part != row.part()
                    || min != null && row.pitch() < min || max != null && row.pitch() > max
                    || status != null && row.status != status) continue;
            if (lane != null && (row.allocation == null || !lane.equals(row.allocation.lane))) continue;
            notes.add(row);
        }
        return JSON.toJsonTree(Map.of("notes", notes)).getAsJsonObject();
    }

    public static JsonObject diff(Path oldWork, Path newWork, String spanOption) throws IOException {
        Span span = null;
        if (spanOption != null) {
            String[] bounds = spanOption.split(",", -1);
            if (bounds.length != 2) throw new IllegalArgumentException("--span expects START,END in source seconds");
            span = new Span(Double.parseDouble(bounds[0]), Double.parseDouble(bounds[1]));
        }
        var result = diff(read(oldWork), read(newWork), span);
        var before = optional(oldWork, "voicing.json", ReconstructionVoicing.Result.class);
        var after = optional(newWork, "voicing.json", ReconstructionVoicing.Result.class);
        result.add("voicingChanges", JSON.toJsonTree(ReconstructionVoicing.changes(before, after)));
        return result;
    }

    static JsonObject diff(Map<String, Row> old, Map<String, Row> updated, Span span) {
        var ids = new LinkedHashSet<>(old.keySet());
        ids.addAll(updated.keySet());
        var changes = new ArrayList<Change>();
        for (String id : ids) {
            Row before = old.get(id), after = updated.get(id);
            var stages = changedStages(before, after);
            if (stages.isEmpty()) continue;
            changes.add(new Change(id, span == null ? null : !overlaps(span, before) && !overlaps(span, after),
                    stages, summary(before), summary(after)));
        }
        return JSON.toJsonTree(Map.of("changes", changes, "lanePermutations", permutations(old, updated, span)))
                .getAsJsonObject();
    }

    private static List<LanePermutation> permutations(Map<String, Row> old, Map<String, Row> updated, Span span) {
        var active = new ArrayList<PermutationGroup>();
        var finished = new ArrayList<PermutationGroup>();
        var ids = new LinkedHashSet<>(old.keySet());
        ids.addAll(updated.keySet());
        var chronological = ids.stream().sorted(Comparator.comparingDouble(id -> attackTime(
                old.getOrDefault(id, updated.get(id))))).toList();
        for (String id : chronological) {
            Row before = old.get(id), after = updated.get(id);
            // The parent attack is the editable target for quantized figure steps.
            if (isFigureStep(before) || isFigureStep(after)) continue;
            String from = allocatedLane(before), to = allocatedLane(after);
            boolean inSpan = span != null && (overlaps(span, before) || overlaps(span, after));
            boolean moved = !inSpan && from != null && to != null && !from.equals(to);
            var joined = new PermutationGroup();
            for (var iterator = active.iterator(); iterator.hasNext();) {
                var group = iterator.next();
                if (!group.touches(from, to)) continue;
                iterator.remove();
                if (moved && group.accepts(from, to)) joined.merge(group);
                else finished.add(group);
            }
            if (moved) {
                joined.add(before, from, to);
                active.add(joined);
            }
        }
        finished.addAll(active);
        finished.sort(Comparator.comparingDouble(group -> group.start));
        var result = new ArrayList<LanePermutation>();
        for (var group : finished) result.add(group.finish(result.size() + 1));
        return List.copyOf(result);
    }

    private static boolean isFigureStep(Row row) {
        return row != null && row.roles == null && row.allocation != null && row.id.contains("-step-");
    }

    private static String allocatedLane(Row row) {
        return row == null || row.allocation == null || row.allocation.placement == null ? null : row.allocation.lane;
    }

    private static double attackTime(Row row) {
        return allocatedLane(row) == null ? row.timing.sourceStart : row.allocation.placement.sourceStart;
    }

    private static List<String> changedStages(Row a, Row b) {
        if (a == null || b == null) return List.of("presence");
        var stages = new ArrayList<String>();
        if (!sameNote(a.score, b.score) || !Objects.equals(a.scoreDecision, b.scoreDecision)) stages.add("score");
        if (!sameNote(a.structure == null ? null : a.structure.effective, b.structure == null ? null : b.structure.effective))
            stages.add("structure");
        if (!sameRoles(a.roles, b.roles)) stages.add("roles");
        if (!samePlacement(a.allocation, b.allocation)) stages.add("allocate");
        if (!a.voiced.stream().map(ReconstructionTrace::voicedFate).toList()
                .equals(b.voiced.stream().map(ReconstructionTrace::voicedFate).toList())) stages.add("voice");
        if ((a.status != b.status || !a.reason.equals(b.reason)) && !stages.contains(b.stage)) stages.add(b.stage);
        return List.copyOf(stages);
    }

    private static boolean sameNote(NoteAt a, NoteAt b) {
        return a == b || a != null && b != null && a.timing.equals(b.timing)
                && a.note.part() == b.note.part() && a.note.pitch() == b.note.pitch()
                && a.note.velocity() == b.note.velocity() && Objects.equals(a.note.bend(), b.note.bend())
                && Objects.equals(a.note.run(), b.note.run());
    }

    private static boolean sameRoles(Roles a, Roles b) {
        return a == b || a != null && b != null && a.role == b.role && sameNote(a.effective, b.effective)
                && Objects.equals(a.mergedInto, b.mergedInto) && a.mergedFrom.equals(b.mergedFrom);
    }

    private static boolean samePlacement(Allocation a, Allocation b) {
        return a == b || a != null && b != null && a.outcome == b.outcome && Objects.equals(a.lane, b.lane)
                && Objects.equals(a.placement, b.placement) && sameNote(a.effective, b.effective)
                && Objects.equals(a.mergedInto, b.mergedInto) && Objects.equals(a.competitorNoteId, b.competitorNoteId)
                && Objects.equals(a.blockingNoteIds, b.blockingNoteIds) && a.figures.equals(b.figures);
    }

    private static Voiced voicedFate(Voiced gate) {
        return new Voiced(gate.id, gate.lane, gate.pitch, gate.gate, gate.bend, null);
    }

    private static Summary summary(Row row) {
        if (row == null) return null;
        var role = row.roles;
        var allocated = row.allocation;
        return new Summary(row.part, row.pitch, row.timing, row.status, row.stage, row.reason,
                role == null ? null : role.role, role == null ? null : role.reason,
                allocated == null ? null : allocated.lane, allocated == null ? null : allocated.placement,
                role == null ? allocated == null ? null : allocated.mergedInto : role.mergedInto,
                allocated == null ? null : allocated.competitorNoteId,
                allocated == null ? null : allocated.blockingNoteIds, row.voiced);
    }

    private static boolean overlaps(Span span, Row row) {
        return row != null && (span.overlaps(row.timing) || row.score != null && span.overlaps(row.score.timing));
    }

    static Map<String, Row> read(Path work) throws IOException {
        return read(work, ReconstructionStages.readScore(work.resolve("score.json")));
    }

    static Map<String, Row> read(Path work, ReconstructionStages.Score score) throws IOException {
        var structure = optional(work, "structure.json", ReconstructionStructure.Result.class);
        var roles = optional(work, "roles.json", ReconstructionRoles.Result.class);
        var allocation = optional(work, "allocation.json", ReconstructionAllocation.Result.class);
        var voicing = optional(work, "voicing.json", ReconstructionVoicing.Result.class);
        var timeline = ReconstructionTimeMap.of(score);
        var reportPath = work.resolve("report.json");
        var report = Files.exists(reportPath) ? ReconstructionArtifacts.readTree(reportPath).getAsJsonObject() : new JsonObject();
        var played = playback(score, allocation, voicing, report, timeline);

        var scored = index(score.notes(), Note::id);
        var structured = index(structure == null ? List.of() : structure.notes(), Note::id);
        var assigned = index(roles == null ? List.of() : roles.notes(), entry -> entry.note().id());
        var allocated = index(allocation == null ? List.of() : allocation.notes(), entry -> entry.note().id());
        var lanes = LaneEvidence.of(allocated.values());
        var runs = index(allocation == null || allocation.fastRuns() == null ? List.of() : allocation.fastRuns(),
                ReconstructionAllocation.RunPlacement::id);
        var parts = index(score.source().parts(), MidiScore.Part::id);
        var corrections = group(structure == null ? List.of() : structure.corrections(), entry -> entry.note().id());
        var source = origins(score, scored);
        var editReasons = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var result : ReconstructionEdits.Journal.read(work).all()) {
            if (result.status() == ReconstructionEdits.Status.applied || result.status() == ReconstructionEdits.Status.conflict)
                for (String id : result.noteIds())
                    editReasons.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(result.tag());
            if (result.op() == ReconstructionEdits.Kind.deleteNote && result.original() != null)
                source.putIfAbsent(result.original().id(), new Raw(result.original(), null, result.tag()));
        }
        var scoreDecisions = index(score.scoreDecisions() == null ? List.of() : score.scoreDecisions(),
                entry -> entry.note().id());
        var refinements = refinements(score.pitchChanges());
        var roleInputs = roles == null ? null : roles.structureNoteHashes();
        var structureInputs = structure == null ? null : structure.scoreNoteHashes();
        var mergePartners = group(roles == null ? List.of() : roles.notes().stream()
                .filter(decision -> decision.mergedInto() != null).toList(), ReconstructionRoles.Decision::mergedInto);
        var ids = new LinkedHashSet<>(source.keySet());
        ids.addAll(scored.keySet());
        ids.addAll(structured.keySet());
        ids.addAll(assigned.keySet());
        ids.addAll(allocated.keySet());

        var rows = new ArrayList<Row>();
        for (String id : ids) {
            var raw = source.get(id);
            var written = at(scored.get(id), TimeBase.source, timeline);
            var effective = at(structured.get(id), structure == null ? null : structure.timeBase(), timeline);
            var role = assigned.get(id);
            var placed = allocated.get(id);
            var run = runs.get(id);
            var roleNote = role == null ? null : at(role.note(), roles.timeBase(), timeline);
            var placedNote = placed == null ? null : at(placed.note(), allocation.timeBase(), timeline);
            var origin = raw == null ? null : origin(raw, refinements, timeline);
            var anchor = first(origin == null ? null : origin.note, written, effective, roleNote, placedNote);
            var part = parts.get(anchor.note.part());
            boolean noise = part != null && ReconstructionAllocation.isNoise(part.name())
                    && (score.inputs() == null || !score.inputs().fmOnly());
            var expected = placed != null || role != null ? ReconstructionAllocation.expectedLanes(
                    role == null ? placed.role() : role.role(), noise) : List.<String>of();
            var fixes = corrections.getOrDefault(id, List.of());
            var scoreDecision = scoreDecisions.get(id);
            var roleTrace = role == null ? null : new Roles(roleNote, role.role(), roleReason(role, score),
                    role.mergedInto(), role.preLegatoEnd(),
                    mergePartners.getOrDefault(id, List.of()).stream().map(decision -> decision.note().id()).toList());
            var placement = placement(placed, run, played.figures.get(id), placedNote, expected, lanes, timeline);
            var voiced = new ArrayList<>(played.byId.getOrDefault(id, List.of()));
            if (run != null || played.figures.containsKey(id)) voiced.addAll(played.figureGates.getOrDefault(id, List.of()));
            boolean staleScore = written != null && !matchesInput(structureInputs, timeline.projectNote(written.note));
            boolean staleStructure = effective != null && !matchesInput(roleInputs, effective.note);
            var fate = fate(raw, origin, written, effective, roleTrace, placement, voiced, played.unmatched.containsKey(id),
                    fixes, scoreDecision, structure != null, roles != null, allocation != null,
                    structureInputs == null, staleScore, staleStructure);
            var edits = new LinkedHashSet<>(editReasons.getOrDefault(id, new LinkedHashSet<>()));
            addEditReason(edits, fate.reason);
            if (placement != null) {
                addEditReason(edits, placement.reason);
                var blockers = new ArrayList<String>();
                if (placement.competitorNoteId != null) blockers.add(placement.competitorNoteId);
                if (placement.blockingNoteIds != null) blockers.addAll(placement.blockingNoteIds);
                if (placement.outcome != ReconstructionAllocation.Outcome.kept)
                    for (String blocker : blockers) {
                        var pin = roles == null || roles.lanePins() == null ? null : roles.lanePins().get(blocker);
                        if (pin != null) edits.add(pin.tag());
                    }
            }
            String reason = edits.isEmpty() ? fate.reason : String.join(" ", edits);
            rows.add(new Row(id, anchor.note.part(), part == null ? "" : part.name(), anchor.note.pitch(), anchor.timing,
                    origin, written, scoreDecision, effective == null && fixes.isEmpty() ? null
                            : new Structure(effective, fixes),
                    roleTrace, placement, List.copyOf(voiced), fate.status, fate.stage, reason,
                    edits.isEmpty() ? null : List.copyOf(edits), reason.equals(fate.reason) ? null : fate.reason));
        }
        rows.sort(Comparator.comparingDouble((Row row) -> row.timing.sourceStart).thenComparingInt(Row::part)
                .thenComparingInt(Row::pitch).thenComparing(Row::id));
        return index(rows, Row::id);
    }

    private static void addEditReason(Set<String> edits, String reason) {
        if (reason == null) return;
        for (String token : reason.split(" ")) if (token.startsWith("edit:")) edits.add(token);
    }

    private static Fate fate(Raw raw, Origin origin, NoteAt score, NoteAt structure, Roles roles, Allocation allocation,
                             List<Voiced> voiced, boolean unmatched, List<ReconstructionStructure.Correction> fixes,
                             ReconstructionStages.ScoreDecision scoreDecision,
                             boolean hasStructure, boolean hasRoles, boolean hasAllocation,
                             boolean legacyStructure, boolean staleScore, boolean staleStructure) {
        if (raw != null && score == null) {
            if (scoreDecision != null) return new Fate(Status.merged, "score", scoreDecision.reason());
            if (raw.decision != null) return new Fate(Status.dropped, "score", raw.decision);
            return new Fate(Status.dropped, "score", "source-note-absent-from-score");
        }
        if (score != null && hasStructure) {
            if ("external".equals(score.note.source()) && structure == null)
                return new Fate(Status.dropped, "structure", "repeat-evidence-only");
            if (structure == null) return new Fate(Status.dropped, "structure", !legacyStructure && !staleScore
                    ? "removed-from-structure" : "score-note-did-not-reach-structure");
            var applied = fixes.stream().filter(ReconstructionStructure.Correction::applied).reduce((a, b) -> b);
            boolean legacyMismatch = legacyStructure && applied.isEmpty() && !sameWrittenNote(score, structure)
                    && (roles == null || !sameWrittenNote(roles.effective, structure)
                    || sameWrittenNote(roles.effective, score));
            if (staleScore || legacyMismatch)
                return new Fate(Status.dropped, "structure", "score-edit-not-propagated");
        }
        if (roles == null && structure != null && hasRoles)
            return new Fate(Status.dropped, "roles", "structure-note-did-not-reach-roles");
        if (staleStructure)
            return new Fate(Status.dropped, "roles", "structure-edit-not-propagated");
        if (roles != null && roles.role == ReconstructionRoles.Role.drop)
            return new Fate(roles.mergedInto == null ? Status.dropped : Status.merged, "roles", roles.reason);
        if (allocation == null && roles != null && hasAllocation)
            return new Fate(Status.dropped, "allocate", "role-note-did-not-reach-allocation");
        if (allocation != null) {
            if (roles != null && allocation.effective != null
                    && (!roles.effective.note.equals(allocation.effective.note) || roles.role != allocation.role))
                return new Fate(Status.dropped, "allocate", "roles-edit-not-propagated");
            if (allocation.outcome == ReconstructionAllocation.Outcome.dropped)
                return new Fate(allocation.mergedInto == null ? Status.dropped : Status.merged, "allocate", allocation.reason);
            if (unmatched && voiced.isEmpty())
                return new Fate(Status.dropped, "assemble", "no-playable-catalogue-match");
            if (allocation.outcome == ReconstructionAllocation.Outcome.shortened)
                return new Fate(Status.shortened, "allocate", allocation.reason);
            if (allocation.placement != null && voiced.size() == 1
                    && voiced.getFirst().gate.gateTicks < allocation.placement.gateTicks)
                return new Fate(Status.shortened, "voice", "voicing-articulation");
        }
        if (roles != null && shorter(roles.effective, structure))
            return new Fate(Status.shortened, "roles", Objects.requireNonNullElse(roles.reason, "role-gate-reduced"));
        if (shorter(structure, score)) return new Fate(Status.shortened, "structure", "repeat-or-structure-gate-reduced");
        if (raw != null && score != null && score.timing.sourceGate + 1e-8 < origin.note.timing.sourceGate) {
            String reason = !origin.pitchRefinement.isEmpty() && origin.parentNoteId == null
                    ? origin.pitchRefinement.getFirst().reason() : raw.decision;
            return new Fate(Status.shortened, "score", scoreDecision == null
                    ? Objects.requireNonNullElse(reason, "score-gate-reduced") : scoreDecision.reason());
        }
        if (allocation != null && allocation.lane != null && !allocation.expectedLanes.contains(allocation.lane))
            return new Fate(Status.moved, "allocate", allocation.reason == null ? "outside-primary-role-lanes" : allocation.reason);
        if (allocation != null) return new Fate(Status.kept, "allocate", "placed");
        return new Fate(Status.kept, roles != null ? "roles" : structure != null ? "structure" : "score", "downstream-stage-pending");
    }

    private static boolean matchesInput(Map<String, String> hashes, Note note) {
        return hashes == null || ReconstructionArtifacts.fingerprint(note).equals(hashes.get(note.id()));
    }

    private static String roleReason(ReconstructionRoles.Decision role, ReconstructionStages.Score score) {
        if (role.reason() != null) return role.reason();
        if (role.assignmentReason() != null) return role.assignmentReason();
        return ReconstructionRoles.assignmentReason(role.role(), score.inputs() != null
                && Objects.equals(score.inputs().leadPart(), role.note().part()));
    }

    private static boolean shorter(NoteAt current, NoteAt previous) {
        return current != null && previous != null && current.timing.projectGate + 1e-8 < previous.timing.projectGate;
    }

    private static boolean sameWrittenNote(NoteAt a, NoteAt b) {
        return a.note.pitch() == b.note.pitch() && a.note.part() == b.note.part() && a.note.velocity() == b.note.velocity()
                && Math.abs(a.timing.projectStart - b.timing.projectStart) < 1e-8
                && Math.abs(a.timing.projectEnd - b.timing.projectEnd) < 1e-8;
    }

    private static Allocation placement(ReconstructionAllocation.Entry entry, ReconstructionAllocation.RunPlacement run,
                                        List<ReconstructionAllocation.RunPlacement> retriggers, NoteAt note,
                                        List<String> expected, LaneEvidence lanes,
                                        ReconstructionTimeMap timeline) {
        if (entry == null && run == null) return null;
        var figures = new ArrayList<ReconstructionAllocation.RunPlacement>();
        if (run != null) figures.add(run);
        if (retriggers != null) figures.addAll(retriggers);
        if (entry == null) return new Allocation(null, null, expected, run.lane(), ticks(run.startTick(), run.endTick(), timeline),
                ReconstructionAllocation.Outcome.kept, run.reason(), null, null, null, null, List.copyOf(figures));
        String competitor = entry.competitorNoteId();
        List<String> blockers = entry.blockingNoteIds();
        // Pre-audit artifacts can identify surviving competitors from the lane intervals.
        if (competitor == null && entry.outcome() == ReconstructionAllocation.Outcome.shortened)
            competitor = lanes.next(entry.lane(), entry.endTick());
        if (competitor == null && blockers == null && entry.outcome() == ReconstructionAllocation.Outcome.dropped
                && entry.reason() != null) {
            int tick = (int) Math.clamp(note.timing.startTick, 0, timeline.frames() - 1);
            var occupied = lanes.blockers(entry, expected, tick);
            if (occupied.size() == 1) competitor = occupied.getFirst();
            if (occupied.size() > 1) blockers = occupied;
        }
        return new Allocation(note, entry.role(), expected, entry.lane(), ticks(entry.startTick(), entry.endTick(), timeline),
                entry.outcome(), entry.reason(), entry.mergedInto(), competitor, blockers, entry.relocatedFrom(),
                List.copyOf(figures));
    }

    private static Map<String, Raw> origins(ReconstructionStages.Score score, Map<String, Note> written) {
        var raw = new LinkedHashMap<String, Raw>();
        var reductions = group(score.reductions(), reduction -> ReconstructionStages.midiNoteKey(reduction.note()));
        var instances = new HashMap<String, Integer>();
        var replacedParts = score.inputs() == null || score.inputs().externalNotes() == null
                ? Map.<Integer, ExternalTranscribers.PartNotes>of() : score.inputs().externalNotes();
        for (var note : score.source().notes()) {
            if (note.startUs() >= score.durationUs()) continue;
            String key = ReconstructionStages.midiNoteKey(note);
            var decisions = reductions.getOrDefault(key, List.of());
            String decision = null;
            for (var reduction : decisions) {
                var cut = reduction.note();
                if (cut.velocity() == note.velocity() && (cut.endUs() == note.endUs()
                        || cut.endUs() == Math.min(note.endUs(), score.durationUs())))
                    decision = reduction.change().name().toLowerCase(Locale.ROOT).replace('_', '-');
            }
            // Preparation numbers only surviving notes. Preserve those IDs when a duplicate was discarded.
            boolean excluded = "octave-double".equals(decision) || "pitch-out-of-range".equals(decision);
            String id = excluded ? key + "-prepared-drop-" + instances.merge(key + "-dropped", 1, Integer::sum)
                    : ReconstructionStages.noteInstance(key, instances.merge(key, 1, Integer::sum));
            var external = replacedParts.get(note.partId());
            if (external != null && external.provenance().replace() && !excluded)
                decision = "external-transcriber-replacement";
            var prepared = written.get(id);
            raw.put(id, new Raw(new Note(id, note.partId(), note.startUs() / 1e6, note.endUs() / 1e6, note.pitch(),
                    note.velocity(), prepared != null && "audio-refine".equals(prepared.source()) ? "audio-refine" : "midi",
                    1), null, decision));
        }
        for (var entry : replacedParts.entrySet()) {
            var external = entry.getValue();
            for (int i = 0; i < external.notes().size(); i++) {
                var note = external.notes().get(i);
                if (note.start() >= score.durationUs() / 1e6) continue;
                String id = "transcriber-" + entry.getKey() + "-" + i;
                String decision = note.pitch() < 12 || note.pitch() > 106 ? "pitch-out-of-range"
                        : note.end() > score.durationUs() / 1e6 ? "cut-at-end" : null;
                raw.put(id, new Raw(new Note(id, entry.getKey(), note.start(), note.end(), note.pitch(),
                        note.velocity(), "transcriber", 1), external.provenance(), decision));
            }
        }
        for (var note : score.notes()) if ("external".equals(note.source()) || "edit".equals(note.source()))
            raw.putIfAbsent(note.id(), new Raw(note, null, null));
        if (score.scoreDecisions() != null) for (var record : score.scoreDecisions())
            raw.putIfAbsent(record.note().id(), new Raw(record.note(), null, record.reason()));
        return raw;
    }

    private static Map<PartOnset, Refinement> refinements(List<ReconstructionPitchRefinement.Change> changes) {
        var byOriginal = group(changes.stream().filter(change -> change.originalNote() != null).toList(),
                ReconstructionPitchRefinement.Change::originalNote);
        var result = new HashMap<PartOnset, Refinement>();
        for (var entry : byOriginal.entrySet()) {
            var original = entry.getKey();
            var key = new PartOnset(original.partId(), original.startUs());
            result.put(key, new Refinement(original, null, entry.getValue()));
        }
        for (var change : changes) {
            var original = change.originalNote();
            String parent = original == null ? null : ReconstructionStages.midiNoteKey(original);
            var refinement = new Refinement(null, parent, List.of(change));
            result.put(new PartOnset(change.partId(), change.startUs()), refinement);
            if (original != null && change.endUs() < original.endUs())
                result.put(new PartOnset(change.partId(), change.endUs()), refinement);
        }
        return result;
    }

    private static Origin origin(Raw raw, Map<PartOnset, Refinement> refinements, ReconstructionTimeMap timeline) {
        Note note = raw.note;
        var refinement = raw.external != null || "edit".equals(note.source()) ? null : refinements.get(new PartOnset(note.part(), note.midi().startUs()));
        if (refinement == null) return new Origin(at(note, TimeBase.source, timeline), note.source(), raw.external, null, List.of());
        var original = refinement.original;
        String provenance = note.source();
        if (original != null) {
            note = note.withTiming(original.startUs() / 1e6, original.endUs() / 1e6);
            provenance = "midi";
        }
        return new Origin(at(note, TimeBase.source, timeline), provenance, null, refinement.parentId, refinement.changes);
    }

    private record Playback(Map<String, List<Voiced>> byId, Map<String, S3kReconstruction.Reduction> unmatched,
                            Map<String, List<ReconstructionAllocation.RunPlacement>> figures,
                            Map<String, List<Voiced>> figureGates) {}

    private static Playback playback(ReconstructionStages.Score score, ReconstructionAllocation.Result allocation,
                                     ReconstructionVoicing.Result voicing, JsonObject report, ReconstructionTimeMap timeline) {
        if (allocation == null || voicing == null) return new Playback(Map.of(), Map.of(), Map.of(), Map.of());
        boolean exact = report.has("voicedGates") && JSON.toJsonTree(ReconstructionCli.assemblyInputs(score, allocation, voicing))
                .equals(report.get("assemblyInputs"));
        boolean legacy = report.has("version") && !report.has("assemblyInputs");
        var reductions = index(reportList(report, "reductions", S3kReconstruction.Reduction.class, exact || legacy).stream()
                .filter(reduction -> reduction.id() != null && reduction.change() == S3kReconstruction.Change.NO_MATCH).toList(),
                S3kReconstruction.Reduction::id);
        var bends = index(reportList(report, "bends", ReconstructionBendEncoding.Applied.class, exact || legacy),
                ReconstructionBendEncoding.Applied::id);
        var lead = voicing.lanes().get("FM1");
        var playback = ReconstructionRetriggers.articulate(allocation.notes(), score.retriggers(), timeline,
                lead == null ? ReconstructionVoicing.WRITTEN : lead.articulation());
        var gates = new ArrayList<Voiced>();
        if (exact) {
            for (var gate : reportList(report, "voicedGates", S3kReconstruction.VoicedGate.class, true)) {
                gates.add(new Voiced(gate.id(), gate.lane(), gate.pitch(), ticks(gate.startTick(), gate.endTick(), timeline),
                        bends.get(gate.id()), "assembled"));
            }
        } else {
            for (var entry : playback.notes()) {
                String id = entry.note().id();
                if (entry.lane() == null || reductions.containsKey(id)) continue;
                var voice = voicing.lanes().get(entry.lane());
                int duration = S3kReconstruction.voicedDuration(entry,
                        voice == null ? ReconstructionVoicing.WRITTEN : voice.articulation());
                gates.add(new Voiced(id, entry.lane(), entry.note().pitch(),
                        ticks(entry.startTick(), entry.startTick() + duration, timeline), bends.get(id),
                        legacy ? "legacy-report-and-voicing" : "voicing-projection"));
            }
        }
        // Group expanded figure steps by their parent note. Retrigger figures have a separate anchor ID.
        var parents = new HashMap<String, String>();
        var figureInfo = new LinkedHashMap<String, List<ReconstructionAllocation.RunPlacement>>();
        if (allocation.fastRuns() != null) for (var figure : allocation.fastRuns()) {
            for (int step : figure.retainedSteps()) parents.put(figure.id() + "-step-" + step, figure.id());
        }
        var bursts = index(score.retriggers() == null ? List.of() : score.retriggers(), burst -> burst.figure().id());
        for (var figure : playback.placements()) {
            var burst = bursts.get(figure.id());
            String parent = burst.anchor();
            figureInfo.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(figure);
            for (int step : figure.retainedSteps()) parents.put(figure.id() + "-step-" + step, parent);
        }
        return new Playback(group(gates, Voiced::id), reductions, figureInfo,
                group(gates.stream().filter(gate -> parents.containsKey(gate.id)).toList(), gate -> parents.get(gate.id)));
    }

    private static <T> List<T> reportList(JsonObject report, String name, Class<T> type, boolean available) {
        if (!available || !report.has(name)) return List.of();
        return report.getAsJsonArray(name).asList().stream().map(value -> JSON.fromJson(value, type)).toList();
    }

    private static <T> T optional(Path work, String name, Class<T> type) throws IOException {
        Path file = work.resolve(name);
        return Files.notExists(file) ? null : ReconstructionArtifacts.read(file, type);
    }

    private static <K, T> Map<K, T> index(List<T> entries, Function<T, K> key) {
        var result = new LinkedHashMap<K, T>();
        for (var entry : entries) result.put(key.apply(entry), entry);
        return result;
    }

    private static <K, T> Map<K, List<T>> group(List<T> entries, Function<T, K> key) {
        var result = new LinkedHashMap<K, List<T>>();
        for (var entry : entries) result.computeIfAbsent(key.apply(entry), ignored -> new ArrayList<>()).add(entry);
        return result;
    }

    private static NoteAt first(NoteAt... choices) {
        for (var choice : choices) if (choice != null) return choice;
        throw new IllegalStateException("Missing note evidence");
    }

    static NoteAt at(Note note, TimeBase base, ReconstructionTimeMap timeline) {
        if (note == null) return null;
        boolean projectTime = base == TimeBase.project;
        double start = projectTime ? note.start() : timeline.project(note.start());
        double end = projectTime ? note.end() : timeline.project(note.end());
        return new NoteAt(note, timing(projectTime ? timeline.source(start) : note.start(),
                projectTime ? timeline.source(end) : note.end(), start, end, Math.round(start * 60), Math.round(end * 60)));
    }

    static Timing ticks(Integer start, Integer end, ReconstructionTimeMap timeline) {
        return start == null || end == null ? null : timing(timeline.source(start / 60.0),
                timeline.source(end / 60.0), start / 60.0, end / 60.0, start, end);
    }

    private static Timing timing(double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                                 long startTick, long endTick) {
        return new Timing(sourceStart, sourceEnd, sourceEnd - sourceStart, projectStart, projectEnd,
                projectEnd - projectStart, startTick, endTick, endTick - startTick);
    }

    static Map<String, String> flags(String[] args, String... supported) {
        var flags = new LinkedHashMap<String, String>();
        var options = List.of(supported);
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!options.contains(flag)) throw new IllegalArgumentException("Unknown option " + flag);
            String value = flag.equals("--json") ? "" : ++i < args.length ? args[i] : null;
            if (value == null) throw new IllegalArgumentException("Missing value for " + flag);
            if (flags.putIfAbsent(flag, value) != null) throw new IllegalArgumentException("Duplicate option " + flag);
        }
        return flags;
    }

    private static Double number(Map<String, String> flags, String key) {
        return flags.containsKey(key) ? Double.valueOf(flags.get(key)) : null;
    }

    private static Integer integer(Map<String, String> flags, String key) {
        return flags.containsKey(key) ? Integer.valueOf(flags.get(key)) : null;
    }

    static void run(String command, String[] args, PrintStream out) throws IOException {
        boolean diff = command.equals("trace-diff");
        int paths = diff ? 2 : 1;
        if (args.length < paths) throw new IllegalArgumentException(diff
                ? "trace-diff expects OLD_WORKDIR NEW_WORKDIR" : "trace expects WORKDIR");
        String[] options = java.util.Arrays.copyOfRange(args, paths, args.length);
        boolean json = List.of(options).contains("--json");
        JsonObject result = diff
                ? diff(Path.of(args[0]), Path.of(args[1]), flags(options, "--span", "--json").get("--span"))
                : trace(Path.of(args[0]), java.util.Arrays.stream(options).filter(arg -> !arg.equals("--json")).toArray(String[]::new));
        if (json) {
            out.println(JSON.toJson(result));
        } else if (diff) {
            for (var entry : result.getAsJsonArray("changes")) {
                var change = JSON.fromJson(entry, Change.class);
                out.printf("%s %s%s%n", change.id, change.changedStages,
                        Boolean.TRUE.equals(change.outsideSpan) ? " [outside edited span]" : "");
                if (change.old != null) printSummary(out, "old", change.old);
                if (change.updated != null) printSummary(out, "new", change.updated);
            }
            out.printf("%d changed notes%n", result.getAsJsonArray("changes").size());
            for (var entry : result.getAsJsonArray("lanePermutations")) {
                var group = JSON.fromJson(entry, LanePermutation.class);
                String mapping = group.lanes.entrySet().stream()
                        .map(move -> move.getKey() + "→" + move.getValue())
                        .collect(java.util.stream.Collectors.joining(", "));
                if (group.lanes.size() == 2 && group.lanes.keySet().containsAll(group.lanes.values()))
                    mapping = String.join("↔", group.lanes.keySet()) + " swapped";
                out.printf(Locale.ROOT, "%s for %d notes, %.3f–%.3f s%n", mapping, group.noteCount,
                        group.startSeconds, group.endSeconds);
                out.println("  first diverging note IDs: " + group.firstNoteIds);
                for (var pin : group.pins)
                    out.println("  " + JSON.toJsonTree(pin));
            }
            for (var entry : result.getAsJsonArray("voicingChanges"))
                out.println(JSON.fromJson(entry, ReconstructionVoicing.Change.class).describe());
        } else {
            for (var entry : result.getAsJsonArray("notes")) print(out, "", JSON.fromJson(entry, Row.class));
            out.printf("%d notes%n", result.getAsJsonArray("notes").size());
        }
    }

    private static void print(PrintStream out, String indent, Row row) {
        out.printf(Locale.ROOT, "%s%s part %d (%s) MIDI %d %s  %s @ %s (%s)%n",
                indent, row.id, row.part, row.partName, row.pitch, format(row.timing), row.status, row.stage, row.reason);
        if (row.fateDetail != null) out.printf("%s  %s%n", indent, row.fateDetail);
        if (row.source != null) {
            out.printf("%s  source %s%n", indent, row.source.transcriber == null
                    ? row.source.provenance : row.source.transcriber.tool() + " " + row.source.transcriber.outputSha256());
            if (row.source.parentNoteId != null) out.printf("%s  refined from %s%n", indent, row.source.parentNoteId);
        }
        if (row.score != null) out.printf("%s  score MIDI %d %s%n", indent, row.score.note.pitch(), format(row.score.timing));
        if (row.structure != null && row.structure.effective != null)
            out.printf("%s  structure MIDI %d %s%s%n", indent, row.structure.effective.note.pitch(),
                    format(row.structure.effective.timing), row.structure.corrections.isEmpty() ? "" : " (repeat evidence)");
        if (row.roles != null) out.printf("%s  roles %s %s%s%s%n", indent, row.roles.role,
                format(row.roles.effective.timing), row.roles.reason == null ? "" : " " + row.roles.reason,
                row.roles.mergedInto == null ? "" : " → " + row.roles.mergedInto);
        if (row.allocation != null) {
            var placement = row.allocation;
            out.printf("%s  allocation %s%s%s%s%n", indent,
                    placement.lane == null ? "dropped" : placement.lane + " " + format(placement.placement),
                    placement.reason == null ? "" : " " + placement.reason,
                    placement.competitorNoteId == null ? "" : " competitor " + placement.competitorNoteId,
                    placement.blockingNoteIds == null ? "" : " blockers " + placement.blockingNoteIds);
        }
        if (row.roles != null && !row.roles.mergedFrom.isEmpty())
            out.printf("%s  retained attack for %s%n", indent, row.roles.mergedFrom);
        if (row.scoreDecision != null)
            out.printf("%s  score figure %s → %s%n", indent, row.scoreDecision.reason(), row.scoreDecision.successorId());
        for (var gate : row.voiced) out.printf("%s  voiced %s MIDI %d %s%s%n", indent, gate.lane, gate.pitch, format(gate.gate),
                gate.bend == null ? "" : " bend " + gate.bend.mode());
    }

    private static void printSummary(PrintStream out, String label, Summary note) {
        out.printf("%s  MIDI %d %s  %s @ %s (%s)%s%n", label, note.pitch, format(note.sourceTiming),
                note.status, note.stage, note.reason, note.lane == null ? "" : " " + note.lane + " " + format(note.placement));
    }

    private static String format(Timing time) {
        return String.format(Locale.ROOT, "source %.3f–%.3fs / project %.3f–%.3fs / ticks %d–%d",
                time.sourceStart, time.sourceEnd, time.projectStart, time.projectEnd, time.startTick, time.endTick);
    }
}
