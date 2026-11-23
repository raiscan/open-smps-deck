package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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

/** An S3K assembly snapshot with frozen allocation and fitted sounds. */
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
        if (project == null || project.project().target() != TargetGame.S3K)
            throw new RebuildNeeded("Build an attached S3K reconstruction first");
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
        catalogue = InstrumentCatalogue.openReconstruction(cataloguePath, TargetGame.S3K);
        var ordered = new ReconstructionAllocation.Result(allocation.version(), allocation.notes().stream()
                .sorted(java.util.Comparator.comparingInt(entry -> entry.startTick() == null
                        ? clock.startTick(entry.note()) : entry.startTick())).toList());
        var scopes = ReconstructionVoicing.validate(voicing, ordered, catalogue, clock);
        gains = report.has("sectionGains") ? List.of(json.fromJson(report.get("sectionGains"),
                ReconstructionSectionLevels.Gain[].class)) : List.of();
        var drumGains = new HashMap<String, Double>();
        if (report.has("drumHitGains"))
            report.getAsJsonObject("drumHitGains").asMap().forEach((id, value) -> drumGains.put(id, value.getAsDouble()));
        saved = new ReconstructionCli.SavedAssembly(scopes, Map.copyOf(drumGains), ReconstructionClipLinks.Layout.read(work));
        score.notes().forEach(note -> notes.put(note.id(), note));
        allocation.notes().stream().filter(row -> row.lane() != null).forEach(row -> allocated.put(row.note().id(), row));
        var baseline = assemble(score, allocation);
        baseline.voicedGates().forEach(gate -> gates.putIfAbsent(gate.id(), gate));
        original = TargetPlayback.prepare(project.project());
        var rebuilt = TargetPlayback.prepare(baseline.project());
        var compiled = original.compiled();
        if (!Arrays.equals(rebuilt.compiled().song(), compiled.song())
                || !rebuilt.compiled().envelopes().equals(compiled.envelopes())
                || !rebuilt.compiled().samples().equals(compiled.samples())) {
            var prefix = S3kProgramPrefix.between(compiled, rebuilt.compiled());
            if (prefix == null || !prefix.equivalent())
                throw new RebuildNeeded("The built audio differs from its saved assembly inputs");
        }
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

    private record ChangedNote(ReconstructionStages.Note note, Gate gate) {}

    static Prepared rebuilt(TargetPlayback playback, ReconstructionAllocation.Result allocation) {
        return new Prepared(null, null, playback, Map.of(), allocation, false);
    }

    public static final class Prepared {
        private final ReconstructionLivePreview owner;
        private final ReconstructionEdits journal;
        private final TargetPlayback playback;
        private final Map<String, ChangedNote> changes;

        private final ReconstructionAllocation.Result allocation;
        private final boolean hasExactGates;

        private Prepared(ReconstructionLivePreview owner, ReconstructionEdits journal, TargetPlayback playback,
                         Map<String, ChangedNote> changes, ReconstructionAllocation.Result allocation, boolean hasExactGates) {
            this.owner = owner;
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
                Gate left = before == null ? owner.gate(owner.gates.get(id)) : before.gate();
                Gate right = after == null ? owner.gate(owner.gates.get(id)) : after.gate();
                result.add(new Gate(Math.min(left.start(), right.start()), Math.max(left.end(), right.end())));
            }
            return result.stream().sorted(java.util.Comparator.comparingDouble(Gate::start)).toList();
        }

        public TargetPlayback playback() { return playback; }

        public void requireCurrent(ReconstructionReview.Revision expected) throws IOException {
            owner.requireInputs();
            ReconstructionReview.requireRevision(owner.work, expected);
        }

        public boolean isBuilt() { return owner != null && playback == owner.original; }

        ReconstructionAllocation.Result allocation() { return allocation; }

        /** Write the checked journal only if the same inputs and journal revision are still on disk. */
        public ReconstructionReview.Revision save(ReconstructionReview.Revision expected) throws IOException {
            if (owner == null) throw new IllegalStateException("A private preview cannot save edits");
            return owner.saveJournal(expected, journal);
        }
    }

    public Prepared prepare(JsonArray entries, double start, double end, double playhead) throws IOException {
        if (!(start >= 0 && end > start && playhead >= start && playhead < end))
            throw new RebuildNeeded("Use an active Chip playback range");
        var journal = ReconstructionReview.parse(entries.deepCopy());
        var suffix = journal.accepted().intents();
        if (suffix.size() < baseEntries.size()) throw new RebuildNeeded("An edit from the built journal changed");
        for (int i = 0; i < baseEntries.size(); i++)
            if (!baseEntries.get(i).equals(suffix.get(i).json()))
                throw new RebuildNeeded("An edit from the built journal changed");
        suffix = suffix.subList(baseEntries.size(), suffix.size());
        if (suffix.isEmpty()) return new Prepared(this, journal, original, Map.of(), allocation, true);

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
                changed.put(id, new ChangedNote(replacement.get(id).note(), gate(played)));
                if (replacement.get(id).note().bend() != null && replacement.get(id).note().bend().editReason() != null
                        && result.bends().stream().noneMatch(bend -> bend.id().equals(id)))
                    throw new RebuildNeeded("Assembly could not encode the edited bend");
            }
            return new Prepared(this, journal, TargetPlayback.prepare(result.project()), changed, edited, true);
        } catch (IllegalArgumentException failure) {
            throw new RebuildNeeded(failure.getMessage());
        }
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
            journal.write(work);
            return ReconstructionReview.revision(work);
        }
    }
}
