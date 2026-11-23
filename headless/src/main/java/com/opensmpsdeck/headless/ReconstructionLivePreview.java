package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** A driver-specific assembly snapshot with frozen allocation and fitted sounds. */
public final class ReconstructionLivePreview {
    public static final class RebuildNeeded extends IOException {
        RebuildNeeded(String detail) { super(detail); }
    }

    private record Stamp(Object key, java.nio.file.attribute.FileTime modified, long size) {
        static Stamp read(Path file) throws IOException {
            if (Files.notExists(file, NOFOLLOW_LINKS)) return null;
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size());
        }
    }

    private final Path work;
    private final Map<Path, Stamp> inputs = new HashMap<>();
    private final ReconstructionStages.Score score;
    private final ReconstructionStructure.Result structure;
    private final ReconstructionRoles.Result roles;
    private final ReconstructionAllocation.Result allocation;
    private final ReconstructionVoicing.Result voicing;
    private final ReconstructionTimeMap clock;
    private final InstrumentCatalogue catalogue;
    private final ReconstructionEdits base;
    private final JsonArray baseEntries;
    private final ReconstructionCli.SavedAssembly saved;
    private final List<ReconstructionSectionLevels.Gain> gains;
    private final Map<String, ReconstructionStages.Note> notes = new HashMap<>();
    private final Map<String, ReconstructionAllocation.Entry> allocated = new HashMap<>();
    private final Map<String, S3kReconstruction.VoicedGate> gates = new HashMap<>();
    private final TargetPlayback original;
    private final boolean exactBaseline;

    public static ReconstructionLivePreview open(Path work, ReconstructionReview.Revision revision) throws IOException {
        return new ReconstructionLivePreview(work, revision);
    }

    private ReconstructionLivePreview(Path work, ReconstructionReview.Revision revision) throws IOException {
        this.work = work;
        for (String file : List.of("score.json", "structure.json", "allocation.json", "voicing.json",
                "roleplan.json", "roles.json", "assembly-layout.json", "edit-results.json", "report.json", ReconstructionReview.ARRANGEMENT))
            remember(work.resolve(file));
        ReconstructionReview.requireRevision(work, revision);
        var project = ReconstructionReview.requireAttached(work, false);
        if (project == null || project.project().nativeProgram() != null)
            throw new RebuildNeeded("Build an attached editable reconstruction first");
        score = ReconstructionStages.readScore(work.resolve("score.json"));
        clock = ReconstructionTimeMap.of(score);
        structure = ReconstructionStages.readStructure(work.resolve("structure.json"), score);
        roles = ReconstructionTrace.optional(work, "roles.json", ReconstructionRoles.Result.class);
        allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        var journal = ReconstructionEdits.Journal.read(work);
        var builtIds = new HashSet<String>();
        journal.all().forEach(outcome -> builtIds.add(outcome.id()));
        base = new ReconstructionEdits(ReconstructionReview.parse(revision.entries()).accepted().intents().stream()
                .filter(edit -> builtIds.contains(edit.id())).toList());
        baseEntries = new JsonArray();
        for (var edit : base.intents()) baseEntries.add(edit.json());
        if (!Objects.equals(journal.scoreRecipe(), base.scoreRecipe(work)))
            throw new RebuildNeeded("The built score uses a different edit journal");
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        var json = new Gson();
        if (!json.toJsonTree(ReconstructionCli.assemblyInputs(score, allocation, voicing)).equals(report.get("assemblyInputs")))
            throw new RebuildNeeded("Assembly inputs changed");
        if (flag(report, "echoBankLimit") || flag(report, "bendSizeLimit")
                || report.getAsJsonArray("reductions").asList().stream().anyMatch(row ->
                    "audio-only-bank-fit".equals(row.getAsJsonObject().get("reason").getAsString())))
            throw new RebuildNeeded("The built song uses bank size reductions");
        var cataloguePath = Path.of(score.inputs().catalogue());
        remember(cataloguePath);
        catalogue = InstrumentCatalogue.openReconstruction(cataloguePath, score.profile().game());
        var ordered = new ReconstructionAllocation.Result(allocation.version(), allocation.notes().stream()
                .sorted(java.util.Comparator.comparingInt(entry -> entry.startTick() == null
                        ? clock.startTick(entry.note()) : entry.startTick())).toList());
        var scopes = ReconstructionVoicing.validate(voicing, ordered, catalogue, clock);
        gains = report.has("sectionGains") ? List.of(json.fromJson(report.get("sectionGains"),
                ReconstructionSectionLevels.Gain[].class)) : List.of();
        var drumGains = new HashMap<String, Double>();
        if (report.has("drumHitGains"))
            report.getAsJsonObject("drumHitGains").asMap().forEach((id, value) -> drumGains.put(id, value.getAsDouble()));
        // The baseline assembles with the same pure loudness probes as every warm edit.
        var levels = new ReconstructionLanePolicies.Levels(score.profile(),
                ReconstructionPsgEnvelopes.classicCatalogueEnvelopes(catalogue, score.profile().game()), 4096);
        saved = new ReconstructionCli.SavedAssembly(scopes, Map.copyOf(drumGains),
                ReconstructionClipLinks.Layout.read(work), levels);
        score.notes().forEach(note -> notes.put(note.id(), note));
        allocation.notes().stream().filter(row -> row.lane() != null).forEach(row -> allocated.put(row.note().id(), row));
        var baseline = assemble(score, allocation);
        baseline.voicedGates().forEach(gate -> gates.putIfAbsent(gate.id(), gate));
        original = TargetPlayback.prepare(project.project());
        var rebuilt = TargetPlayback.prepare(baseline.project());
        exactBaseline = sameAudio(original, rebuilt);
        requireInputs();
        ReconstructionReview.requireRevision(work, revision);
    }

    private static boolean flag(com.google.gson.JsonObject report, String key) {
        return report.has(key) && report.get(key).getAsBoolean();
    }

    private void remember(Path file) throws IOException { inputs.put(file, Stamp.read(file)); }

    private void requireInputs() throws IOException {
        for (var input : inputs.entrySet())
            if (!Objects.equals(input.getValue(), Stamp.read(input.getKey()))) throw new ReconstructionReview.Changed();
    }

    private S3kReconstruction.Result assemble(ReconstructionStages.Score editedScore,
                                               ReconstructionAllocation.Result editedAllocation) throws IOException {
        return assemble(editedScore, editedAllocation, base, new ArrayList<>());
    }

    private S3kReconstruction.Result assemble(ReconstructionStages.Score editedScore,
                                               ReconstructionAllocation.Result editedAllocation,
                                               ReconstructionEdits edits, List<ReconstructionEdits.Outcome> outcomes) throws IOException {
        var assembly = ReconstructionCli.prepareAssembly(work, editedScore, structure, editedAllocation,
                voicing, catalogue, edits, outcomes, false, gains, saved);
        return assembly.arrangement().arrange(Map.of(), true, true, -1, 0);
    }

    /**
     * Validate and assemble the suffix over the already-built score. Journal entries in the built prefix
     * are already reflected in the score and allocation and must never be replayed over them.
     */
    public record Gate(double start, double end) {}

    private record ChangedNote(ReconstructionStages.Note note, Gate gate, String lane) {}

    static Prepared rebuilt(TargetPlayback playback, ReconstructionAllocation.Result allocation) {
        return new Prepared(null, null, playback, Map.of(), allocation, false);
    }

    /** Prepare an isolated, already built arrangement for the live comparison transport. */
    public static Prepared forComparison(StudioProject project) {
        return rebuilt(TargetPlayback.prepare(project), null);
    }

    public static final class Prepared {
        private final ReconstructionLivePreview owner;
        private final ReconstructionEdits journal;
        private final TargetPlayback playback;
        private final Map<String, ChangedNote> changes;

        private final ReconstructionAllocation.Result allocation;
        private final boolean hasExactGates;
        private final boolean provisional;

        private Prepared(ReconstructionLivePreview owner, ReconstructionEdits journal, TargetPlayback playback,
                         Map<String, ChangedNote> changes, ReconstructionAllocation.Result allocation, boolean hasExactGates) {
            this(owner, journal, playback, changes, allocation, hasExactGates, false);
        }

        private Prepared(ReconstructionLivePreview owner, ReconstructionEdits journal, TargetPlayback playback,
                         Map<String, ChangedNote> changes, ReconstructionAllocation.Result allocation,
                         boolean hasExactGates, boolean provisional) {
            this.owner = owner;
            this.provisional = provisional;
            this.hasExactGates = hasExactGates;
            this.allocation = allocation;
            this.journal = journal;
            this.playback = playback;
            this.changes = Map.copyOf(changes);
        }

        /** Union of the old and new gates of notes changed by this journal revision. */
        public List<Gate> changedGates(Prepared previous) {
            if (!hasExactGates || previous != null && (!previous.hasExactGates || previous.owner != owner)) return List.of();
            var prior = previous == null ? Map.<String, ChangedNote>of() : previous.changes;
            var ids = new HashSet<>(changes.keySet());
            ids.addAll(prior.keySet());
            var result = new ArrayList<Gate>();
            for (String id : ids) {
                var before = prior.get(id);
                var after = changes.get(id);
                if (Objects.equals(before, after)) continue;
                Gate baseline = owner.gates.containsKey(id) ? owner.gate(owner.gates.get(id)) : null;
                Gate left = before == null ? baseline : before.gate();
                Gate right = after == null ? baseline : after.gate();
                if (left == null) left = right;
                if (right == null) right = left;
                if (left != null)
                    result.add(new Gate(Math.min(left.start(), right.start()), Math.max(left.end(), right.end())));
            }
            return result.stream().sorted(java.util.Comparator.comparingDouble(Gate::start)).toList();
        }

        public TargetPlayback playback() { return playback; }

        public boolean provisional() { return provisional; }

        /** A complete executed-program match proves identical PCM at every playhead. */
        public boolean sameAudio(Prepared other) {
            return ReconstructionLivePreview.sameAudio(playback, other.playback);
        }

        public void requireCurrent(ReconstructionReview.Revision expected) throws IOException {
            owner.requireInputs();
            ReconstructionReview.requireRevision(owner.work, expected);
        }

        public boolean isBuilt() { return owner != null && playback == owner.original; }

        ReconstructionAllocation.Result allocation() { return allocation; }

        /** Write the checked journal only if the same inputs and journal revision are still on disk. */
        public ReconstructionReview.Revision save(ReconstructionReview.Revision expected) throws IOException {
            if (owner == null || provisional) throw new IllegalStateException("A private preview cannot save edits");
            return owner.saveJournal(expected, journal);
        }
    }

    private static boolean sameAudio(TargetPlayback before, TargetPlayback after) {
        if (before.target() != after.target()) return false;
        var left = before.compiled();
        var right = after.compiled();
        if (!left.envelopes().equals(right.envelopes()) || !left.samples().equals(right.samples())) return false;
        if (Arrays.equals(left.song(), right.song())) return true;
        if (before.target() != TargetGame.S3K) return false;
        var prefix = S3kProgramPrefix.between(left, right);
        return prefix != null && prefix.equivalent();
    }

    public Prepared prepare(JsonArray entries, double start, double end, double playhead) throws IOException {
        requireRange(start, end, playhead);
        var journal = ReconstructionReview.parse(entries);
        var suffix = suffix(journal);
        if (suffix.isEmpty()) return new Prepared(this, journal, original, Map.of(), allocation, true);
        if (!exactBaseline) throw new RebuildNeeded("The built audio differs from its saved assembly inputs");

        if (suffix.stream().allMatch(edit -> edit.firstStage() == ReconstructionStages.Stage.ASSEMBLE)
                && (voicing.arrangement() == null || voicing.arrangement().sectionFit().isEmpty())) {
            try {
                var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
                var result = assemble(score, allocation, journal.accepted(), outcomes);
                result.layers().forEach(layer -> layer.report(outcomes));
                var ids = suffix.stream().map(ReconstructionEdits.Intent::id).collect(java.util.stream.Collectors.toSet());
                for (var outcome : outcomes)
                    if (ids.contains(outcome.id()) && outcome.status() != ReconstructionEdits.Status.applied
                            && outcome.status() != ReconstructionEdits.Status.satisfied)
                        throw new RebuildNeeded(outcome.detail());
                return new Prepared(this, journal, TargetPlayback.prepare(result.project()), Map.of(), allocation, false);
            } catch (IllegalArgumentException failure) {
                throw new RebuildNeeded(failure.getMessage());
            }
        }

        if (!Files.exists(work.resolve("roleplan.json")) && voicing.arrangement() == null && (voicing.voiceAssignments() == null || voicing.voiceAssignments().isEmpty())) {
            var local = ReconstructionLiveAllocation.tryAllocate(score, structure, roles, allocation, journal.accepted(), suffix);
            if (local != null && ReconstructionVoicing.lanesNeedingFit(voicing, ReconstructionVoicing.byLane(local)).isEmpty()) {
                try {
                    var retained = ReconstructionCli.keepVoicing(score, local, voicing, ignored -> {});
                    var canonical = ReconstructionCli.prepareAssembly(work, score, structure, local, retained,
                            catalogue, journal.accepted(), new ArrayList<>(), false, null, null);
                    var project = canonical.arrangement().arrange(Map.of(), true, true, -1, 0).project();
                    return new Prepared(this, journal, TargetPlayback.prepare(project), Map.of(), local, false);
                } catch (IllegalArgumentException failure) {
                    throw new RebuildNeeded(failure.getMessage());
                }
            }
        }

        // A frozen lane projection cannot prove parity when the score itself can
        // change stem-backed recovery, repeat consensus, or measured section gains.
        if (score.inputs().audioOnly() != null || !score.inputs().stems().isEmpty()
                || !structure.repeats().isEmpty() || !structure.corrections().isEmpty()
                || voicing.arrangement() != null && !voicing.arrangement().sectionFit().isEmpty())
            throw new RebuildNeeded("Source evidence can change allocation outside the edited window");

        var targets = new HashSet<String>();
        var dependencies = new HashSet<String>();
        for (var edit : suffix) {
            switch (edit.kind()) {
                case transpose, bend, glide -> { }
                case setNote -> {
                    if (edit.velocity() != null || edit.noExtension() != null)
                        throw new RebuildNeeded("This note edit needs a full rebuild");
                }
                default -> throw new RebuildNeeded("This change needs a full rebuild");
            }
            if (edit.noteIds().isEmpty()) throw new RebuildNeeded("Live editing requires a scored note ID");
            if (edit.kind() == ReconstructionEdits.Kind.glide) {
                targets.add(edit.noteIds().getFirst());
                dependencies.add(edit.noteIds().getLast());
            } else targets.addAll(edit.noteIds());
        }
        dependencies.addAll(targets);
        for (String id : dependencies) {
            var gate = gates.get(id);
            var row = allocated.get(id);
            if (!notes.containsKey(id) || row == null || gate == null || row.startTick() == null || row.endTick() == null
                    || !gate.lane().equals(row.lane())
                    || !row.lane().matches("FM[1-5]|PSG[1-3]") || row.note().run() != null
                    || allocation.chords().containsKey(id))
                throw new RebuildNeeded("The note needs allocation or voicing changes");
        }

        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var editedScore = new ReconstructionEdits(suffix).applyScore(score, outcomes);
        if (outcomes.stream().anyMatch(outcome -> outcome.status() != ReconstructionEdits.Status.applied
                || outcome.missingNoteIds() != null && !outcome.missingNoteIds().isEmpty()))
            throw new RebuildNeeded("The note edit could not be resolved against the built score");
        var replacement = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var after : editedScore.notes()) {
            if (!targets.contains(after.id())) continue;
            String id = after.id();
            var before = notes.get(id);
            var row = allocated.get(id);
            var played = row.note();
            boolean moved = after.start() != before.start();
            boolean resized = after.end() != before.end();
            var mapped = clock.projectNote(after);
            int pitch = played.pitch() + after.pitch() - before.pitch();
            var note = new ReconstructionStages.Note(id, played.part(), moved ? mapped.start() : played.start(),
                    resized ? mapped.end() : played.end(), pitch, played.velocity(), played.source(),
                    played.confidence(), played.motion(), null,
                    after.bend() == before.bend() ? played.bend() : mapped.bend());
            if (note.start() >= note.end() || ReconstructionEdits.transposeIssue(note, note.pitch(),
                    row.lane().startsWith("PSG") ? ReconstructionLaneType.PSG : ReconstructionLaneType.FM,
                    score.profile(), score.inputs().fmOnly()) != null)
                throw new RebuildNeeded("The edited note no longer fits its chip lane");
            int first = moved ? clock.startTick(note) : row.startTick();
            int last = resized || moved ? clock.endTick(note) : row.endTick();
            replacement.put(id, new ReconstructionAllocation.Entry(note, row.role(), row.lane(), first, last, row.outcome(),
                    row.reason(), row.mergedInto(), row.competitorNoteId(), row.blockingNoteIds(),
                    row.relocatedFrom(), row.pinnedLane(), row.laneType()));
        }
        var editedRows = allocation.notes().stream().map(row -> replacement.getOrDefault(row.note().id(), row)).toList();
        for (var row : replacement.values()) {
            if (editedRows.stream().anyMatch(other -> other != row && row.lane().equals(other.lane())
                    && row.startTick() < other.endTick() && other.startTick() < row.endTick()))
                throw new RebuildNeeded("The edited gate overlaps another note on its chip lane");
        }
        var edited = new ReconstructionAllocation.Result(allocation.version(), editedRows, allocation.timeBase(),
                allocation.leadContinuity(), allocation.melodyGapFill(), allocation.fm1InterNoteGaps(),
                allocation.fastRuns(), allocation.chordVoicings());
        try {
            var result = assemble(editedScore, edited);
            var changed = new HashMap<String, ChangedNote>();
            for (String id : targets) {
                var played = result.voicedGates().stream().filter(gate -> gate.id().equals(id)
                        && gate.lane().equals(replacement.get(id).lane())).findFirst().orElseThrow(
                                () -> new RebuildNeeded("Assembly could not keep the edited gate"));
                changed.put(id, new ChangedNote(replacement.get(id).note(), gate(played), played.lane()));
                if (replacement.get(id).note().bend() != null && replacement.get(id).note().bend().editReason() != null
                        && result.bends().stream().noneMatch(bend -> bend.id().equals(id)))
                    throw new RebuildNeeded("Assembly could not encode the edited bend");
            }
            return new Prepared(this, journal, TargetPlayback.prepare(result.project()), changed, edited, true);
        } catch (IllegalArgumentException failure) {
            throw new RebuildNeeded(failure.getMessage());
        }
    }

    private static void requireRange(double start, double end, double playhead) throws RebuildNeeded {
        if (!(start >= 0 && end > start && playhead >= start && playhead < end))
            throw new RebuildNeeded("Use an active Chip playback range");
    }

    private List<ReconstructionEdits.Intent> suffix(ReconstructionEdits journal) throws RebuildNeeded {
        var entries = journal.accepted().intents();
        if (entries.size() < baseEntries.size()) throw new RebuildNeeded("An edit from the built journal changed");
        for (int i = 0; i < baseEntries.size(); i++)
            if (!baseEntries.get(i).equals(entries.get(i).json()))
                throw new RebuildNeeded("An edit from the built journal changed");
        return entries.subList(baseEntries.size(), entries.size());
    }

    /**
     * Project edits onto the saved arrangement and place changed attacks against the occupied
     * lanes in their window. The canonical replay can change source evidence and allocation
     * beyond this window, so this result cannot publish a journal.
     */
    public Prepared prepareProvisional(JsonArray entries, double start, double end, double playhead) throws IOException {
        try {
            return prepare(entries, start, end, playhead);
        } catch (RebuildNeeded needsReplay) {
            if (roles == null) throw needsReplay;
        }
        var journal = ReconstructionReview.parse(entries);
        var suffix = suffix(journal);
        requireRange(start, end, playhead);
        if (suffix.stream().anyMatch(edit -> edit.firstStage() == ReconstructionStages.Stage.VOICES
                || edit.firstStage() == ReconstructionStages.Stage.ALLOCATE))
            throw new RebuildNeeded("This edit needs canonical allocation or voice fitting");
        var edits = new ReconstructionEdits(suffix);
        var outcomes = new ArrayList<ReconstructionEdits.Outcome>();
        var editedScore = edits.bindSections(work, score, structure, ReconstructionStages.Stage.TRANSCRIBE, outcomes)
                .applyScore(work, score, outcomes);
        var after = new HashMap<String, ReconstructionStages.Note>();
        editedScore.notes().forEach(note -> after.put(note.id(), note));

        var written = structure.notes().stream().filter(note -> !notes.containsKey(note.id()) || after.containsKey(note.id()))
                .map(note -> projectEdit(note, after)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var decisions = new java.util.LinkedHashMap<String, ReconstructionRoles.Decision>();
        for (var row : roles.notes()) {
            var id = row.note().id();
            if (notes.containsKey(id) && !after.containsKey(id)) continue;
            var note = projectEdit(row.note(), after);
            decisions.put(id, new ReconstructionRoles.Decision(note, row.role(), row.reason(), row.mergedInto(),
                    row.preLegatoEnd(), row.assignmentReason()));
        }
        for (var note : editedScore.notes()) {
            if (notes.containsKey(note.id())) continue;
            var projected = clock.projectNote(note);
            written.add(projected);
            var part = score.source().parts().stream().filter(candidate -> candidate.id() == note.part())
                    .findFirst().orElseThrow();
            decisions.put(note.id(), new ReconstructionRoles.Decision(projected,
                    part.drums() ? ReconstructionRoles.Role.drums : ReconstructionRoles.Role.harmony, "live insertion", null));
        }
        var localStructure = new ReconstructionStructure.Result(structure.version(), structure.bars(),
                structure.sections(), structure.repeats(), structure.corrections(), written, structure.phrases(),
                structure.roleLayout(), structure.timeBase(), null);
        var localRoles = new ReconstructionRoles.Result(roles.version(), roles.harmonyMaxHz(),
                List.copyOf(decisions.values()), roles.timeBase());
        var classified = edits.bindSections(work, editedScore, localStructure, ReconstructionStages.Stage.ROLES, outcomes)
                .applyRoles(editedScore, localStructure, localRoles, List.copyOf(outcomes), outcomes);
        if (outcomes.stream().anyMatch(outcome -> outcome.status() == ReconstructionEdits.Status.invalid
                || outcome.status() == ReconstructionEdits.Status.conflict))
            throw new RebuildNeeded("The edit could not be previewed against the built score");

        var before = new HashMap<String, ReconstructionRoles.Decision>();
        roles.notes().forEach(row -> before.put(row.note().id(), row));
        var changed = new HashSet<>(before.keySet());
        classified.notes().forEach(row -> {
            String id = row.note().id();
            if (Objects.equals(before.get(id), row) && !pinChanged(classified, id)) changed.remove(id);
            else changed.add(id);
        });
        var inserted = suffix.stream().filter(edit -> edit.kind() == ReconstructionEdits.Kind.insertNote)
                .map(ReconstructionEdits.Intent::insertedId).collect(java.util.stream.Collectors.toSet());
        var edited = ReconstructionLiveAllocation.project(score, structure, allocation, classified, changed, inserted, clock);
        try {
            var result = assemble(editedScore, edited, journal.accepted(), outcomes);
            var previewGates = new HashMap<String, ChangedNote>();
            // Keep old gates for removals so a just-passed deleted gate can still be re-cued.
            for (String id : changed) {
                var old = gates.get(id);
                if (old != null) previewGates.put(id, new ChangedNote(null, gate(old), null));
            }
            var played = new HashMap<String, ReconstructionStages.Note>();
            edited.notes().forEach(row -> played.put(row.note().id(), row.note()));
            for (var row : result.voicedGates())
                if (changed.contains(row.id())) previewGates.put(row.id(), new ChangedNote(played.get(row.id()), gate(row), row.lane()));
            return new Prepared(this, journal, TargetPlayback.prepare(result.project()), previewGates, edited, true, true);
        } catch (IllegalArgumentException failure) {
            throw new RebuildNeeded(failure.getMessage());
        }
    }

    private boolean pinChanged(ReconstructionRoles.Result edited, String id) {
        return edited.lanePins() != null && edited.lanePins().containsKey(id);
    }

    private ReconstructionStages.Note projectEdit(ReconstructionStages.Note played,
                                                    Map<String, ReconstructionStages.Note> after) {
        var before = notes.get(played.id());
        var edited = after.get(played.id());
        if (before == null || Objects.equals(before, edited)) return played;
        var mapped = clock.projectNote(edited);
        double start = before.start() == edited.start() ? played.start() : mapped.start();
        double end = before.end() == edited.end() ? played.end() : mapped.end();
        if (end <= start) return mapped;
        return new ReconstructionStages.Note(played.id(), played.part(), start, end,
                played.pitch() + edited.pitch() - before.pitch(), edited.velocity(), played.source(),
                played.confidence(), played.motion(), played.run(),
                Objects.equals(before.bend(), edited.bend()) ? played.bend() : mapped.bend());
    }

    private Gate gate(S3kReconstruction.VoicedGate gate) {
        return new Gate(clock.secondsAtTick(gate.startTick()), clock.secondsAtTick(gate.endTick()));
    }

    /** Save the checked journal using the same revision and file locks as a canonical publication. */
    private ReconstructionReview.Revision saveJournal(ReconstructionReview.Revision expected,
                                                        ReconstructionEdits journal) throws IOException {
        try (var edits = FileChannel.open(work.resolve(ReconstructionEditCommand.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var editLock = edits.lock();
             var sections = FileChannel.open(work.resolve(ReconstructionSections.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var sectionLock = sections.lock();
             var voices = FileChannel.open(work.resolve(ReconstructionVoicePlan.LOCK), CREATE, WRITE, NOFOLLOW_LINKS);
             var voiceLock = voices.lock()) {
            ReconstructionReview.requireRevision(work, expected);
            requireInputs();
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Live save cancelled");
            journal.write(work);
            return ReconstructionReview.revision(work);
        }
    }
}
