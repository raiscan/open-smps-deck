package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionBranches.*;
import static com.opensmpsdeck.headless.ReconstructionEdits.Kind;
import static com.opensmpsdeck.headless.ReconstructionTrace.Row;
import static com.opensmpsdeck.headless.ReconstructionTrace.Timing;

final class ReconstructionBranchDiff {
    private static final Gson JSON = new Gson();

    private record Scope(int part, Range span) {}

    record Effect(String branch, String id, JsonObject before, JsonObject after, String family,
                  Set<String> notes, Set<Integer> parts, Range span, List<Scope> scopes, boolean ordered) {
        boolean sameChange(Effect other) {
            return id.equals(other.id) && Objects.equals(after, other.after) && !ordered && !other.ordered;
        }

        boolean matches(Selection selection) {
            var entry = after == null ? before : after;
            var window = selection.start() == null && selection.end() == null ? null
                    : new Range(selection.start() == null ? 0 : selection.start(),
                            selection.end() == null ? Double.MAX_VALUE : selection.end());
            return (selection.edits().isEmpty() || selection.edits().contains(id))
                    && (selection.groups().isEmpty() || entry.has("group")
                    && selection.groups().contains(entry.get("group").getAsString()))
                    && (selection.parts().isEmpty() || intersects(selection.parts(), parts))
                    && (window == null || scopes.stream().anyMatch(scope -> scope.span.overlaps(window)
                    && (selection.parts().isEmpty() || selection.parts().contains(scope.part))));
        }

        EditRef ref() { return new EditRef(branch, id); }
    }

    private record NoteState(int part, int pitch, Timing timing, String role, String lane, Integer velocity,
                             Object voice, Object instrument, Object bend, Object vibrato, Object voiced, Object layers) {}
    private record NoteChange(String id, String change, List<String> fields, NoteState before, NoteState after) {}
    private record Sound(String lane, String variant, double gain, ReconstructionVoicing.Effects effects) {}
    private record PartVoicing(int part, List<Sound> before, List<Sound> after) {}

    private ReconstructionBranchDiff() {}

    static JsonObject diff(Path before, Path after, Branch branch) throws IOException {
        var old = notes(before);
        var updated = notes(after);
        var changes = new ArrayList<NoteChange>();
        for (var id : union(old.keySet(), updated.keySet())) {
            var a = old.get(id);
            var b = updated.get(id);
            if (Objects.equals(a, b)) continue;
            var fields = new ArrayList<String>();
            if (a != null && b != null) {
                var left = JSON.toJsonTree(a).getAsJsonObject();
                var right = JSON.toJsonTree(b).getAsJsonObject();
                for (var field : union(left.keySet(), right.keySet()))
                    if (!Objects.equals(left.get(field), right.get(field))) fields.add(field);
            }
            changes.add(new NoteChange(id, a == null ? "added" : b == null ? "removed" : "changed", fields, a, b));
        }
        var oldVoices = voicing(old);
        var newVoices = voicing(updated);
        var voicing = new ArrayList<PartVoicing>();
        for (var part : union(oldVoices.keySet(), newVoices.keySet()))
            if (!Objects.equals(oldVoices.get(part), newVoices.get(part)))
                voicing.add(new PartVoicing(part, oldVoices.getOrDefault(part, List.of()),
                        newVoices.getOrDefault(part, List.of())));
        return JSON.toJsonTree(Map.of("branch", branch.name(), "parent", branch.parent(), "changes", changes,
                "voicingChanges", voicing, "groups", branch.groups(), "outcomes", outcomes(after, branch))).getAsJsonObject();
    }

    private static Map<String, NoteState> notes(Path work) throws IOException {
        var rows = ReconstructionTrace.read(work);
        var plan = ReconstructionTrace.optional(work, ReconstructionVoicePlan.FILE, ReconstructionVoicePlan.Result.class);
        var assignments = plan == null ? Map.<String, ReconstructionVoicePlan.Voice>of() : plan.byNote();
        var voicing = ReconstructionTrace.optional(work, "voicing.json", ReconstructionVoicing.Result.class);
        var byEdit = ReconstructionBranches.indexed(ReconstructionReview.revision(work).entries());
        var authored = new LinkedHashMap<String, String>();
        for (var outcome : ReconstructionEdits.Journal.read(work).all()) {
            if (outcome.op() != Kind.noteVoicing || outcome.status() != ReconstructionEdits.Status.applied) continue;
            var edit = byEdit.get(outcome.id());
            if (edit != null && edit.has("instrument")) for (var id : outcome.noteIds())
                authored.put(id, edit.get("instrument").getAsString());
        }
        var notes = new LinkedHashMap<String, NoteState>();
        for (var row : rows.values()) {
            // The trace retains deleted IDs for diagnostics. Presence here means a surviving score note.
            if (row.score() == null && row.voiced().isEmpty()) continue;
            var note = row.score() == null ? null : row.score().note();
            var role = row.roles() == null || row.roles().role() == null ? null : row.roles().role().name();
            String lane = lane(row);
            var voice = ReconstructionVoicePlan.voiceFor(assignments, row.id());
            var fitted = voicing == null || lane == null ? null : voicing.lanes().get(lane);
            var source = fitted == null ? null : voicing.sourceSound(lane, row.part());
            if (source != null && lane.startsWith("FM")) fitted = source.fm();
            String variant = fitted == null ? null : fitted.instruments().stream()
                    .filter(instrument -> instrument.part() == row.part()
                            && (instrument.drumPitch() == null || instrument.drumPitch() == row.pitch()))
                    .map(ReconstructionVoicing.Instrument::variantId).filter(Objects::nonNull).findFirst().orElse(fitted.variantId());
            if (source != null && lane.startsWith("PSG") && source.psgVariantId() != null) variant = source.psgVariantId();
            if (voice != null && voice.assignment() != null) variant = voice.assignment().instrument();
            variant = authored.getOrDefault(row.id(), variant);
            double gain = fitted == null ? 1 : fitted.partGains() == null ? fitted.gain()
                    : fitted.partGains().getOrDefault(row.part(), fitted.gain());
            var instrument = fitted == null && variant == null ? null : new Sound(lane, variant, gain,
                    fitted == null ? null : fitted.effects());
            notes.put(row.id(), new NoteState(row.part(), note == null ? row.pitch() : note.pitch(), row.effectiveTiming(),
                    role, lane, note == null ? null : note.velocity(), row.voice(), instrument,
                    note == null ? null : note.bend(), row.voiced().stream().map(RowVibrato::of).toList(),
                    row.voiced(), row.layers()));
        }
        return notes;
    }

    private record RowVibrato(String lane, Object envelope) {
        static RowVibrato of(ReconstructionTrace.Voiced note) { return new RowVibrato(note.lane(), note.vibratoEnvelope()); }
    }

    private static Map<Integer, List<Sound>> voicing(Map<String, NoteState> notes) {
        var sounds = new TreeMap<Integer, LinkedHashSet<Sound>>();
        for (var note : notes.values())
            if (note.instrument instanceof Sound sound)
                sounds.computeIfAbsent(note.part, ignored -> new LinkedHashSet<>()).add(sound);
        var result = new TreeMap<Integer, List<Sound>>();
        sounds.forEach((part, values) -> result.put(part, values.stream()
                .sorted(java.util.Comparator.comparing(JSON::toJson)).toList()));
        return result;
    }

    static Map<String, List<ReconstructionReview.EditResult>> outcomes(Path work, Branch branch) throws IOException {
        var all = ReconstructionReview.outcomes(work);
        var result = new LinkedHashMap<String, List<ReconstructionReview.EditResult>>();
        for (var edit : branch.edits()) {
            String id = edit.get("id").getAsString();
            var fallback = new ReconstructionReview.EditResult(
                    ReconstructionProposals.isInactive(edit) && !ReconstructionProposals.isProposed(edit) ? "rejected" : "unresolved",
                    "No outcome was recorded by canonical replay", List.of());
            result.put(id, all.getOrDefault(id, List.of(fallback)));
        }
        if (branch.removedEditIds() != null) for (var id : branch.removedEditIds())
            result.put(id, List.of(new ReconstructionReview.EditResult("removed", "Removed from the journal", List.of())));
        return result;
    }

    static List<Effect> effects(String branch, JsonArray before, JsonArray after, Path oldWork, Path newWork)
            throws IOException {
        var old = indexed(before);
        var updated = indexed(after);
        var oldRows = ReconstructionTrace.read(oldWork);
        var newRows = oldWork.equals(newWork) ? oldRows : ReconstructionTrace.read(newWork);
        var score = ReconstructionStages.readScore(newWork.resolve("score.json"));
        var clock = ReconstructionTimeMap.of(score);
        var journal = ReconstructionEdits.Journal.read(newWork);
        var moved = moved(old, updated);
        var oldOutcomes = ReconstructionEdits.Journal.read(oldWork).all().stream()
                .collect(java.util.stream.Collectors.groupingBy(ReconstructionEdits.Outcome::id));
        var newOutcomes = journal.all().stream()
                .collect(java.util.stream.Collectors.groupingBy(ReconstructionEdits.Outcome::id));
        var result = new ArrayList<Effect>();
        for (var id : union(updated.keySet(), old.keySet())) {
            var a = old.get(id);
            var b = updated.get(id);
            boolean ordered = moved.contains(id);
            if (Objects.equals(a, b) && !ordered) continue;
            var notes = new LinkedHashSet<String>();
            var parts = new LinkedHashSet<Integer>();
            var scopes = new ArrayList<Scope>();
            String family = null;
            for (var entry : Arrays.asList(a, b)) {
                if (entry == null) continue;
                var edit = ReconstructionEdits.parse(entry, id);
                family = family(edit.kind());
                if (edit.part() != null) parts.add(edit.part());
                notes.addAll(edit.noteIds());
                if (edit.kind() == Kind.insertNote) notes.add(edit.insertedId());
                var explicit = edit.explicitSpan();
                if (explicit != null && edit.part() != null) scopes.add(new Scope(edit.part(),
                        new Range(edit.toSource(explicit.start(), clock), edit.toSource(explicit.end(), clock))));
                for (var selector : edit.selectors()) scopes.add(new Scope(selector.part(), point(edit, selector, clock)));
                if (edit.attack() != null) scopes.add(new Scope(edit.attack().part(), point(edit, edit.attack(), clock)));
                if (edit.span() != null && edit.span().section() != null) {
                    var section = ReconstructionSections.load(newWork, score).sections().stream()
                            .filter(value -> value.id().equals(edit.span().section()) || value.label().equalsIgnoreCase(edit.span().section()))
                            .findFirst().orElse(null);
                    if (section != null && edit.part() != null)
                        scopes.add(new Scope(edit.part(), new Range(section.sourceStart(), section.sourceEnd())));
                }
                if (edit.attack() != null) parts.add(edit.attack().part());
                edit.selectors().forEach(selector -> parts.add(selector.part()));
                if (edit.kind() == Kind.voiceEdit) {
                    var voice = ReconstructionVoicePlan.reviewEdit(entry, id);
                    if (voice.noteIds() != null) notes.addAll(voice.noteIds());
                    if (voice.part() != null) parts.add(voice.part());
                    if (voice.span() != null && voice.part() != null) {
                        boolean project = voice.timeBase() == ReconstructionVoicePlan.Clock.project;
                        scopes.add(new Scope(voice.part(), new Range(
                                project ? clock.source(voice.span().getFirst()) : voice.span().getFirst(),
                                project ? clock.source(voice.span().getLast()) : voice.span().getLast())));
                    }
                    if (voice.noteIds() == null && voice.selectors() == null && voice.part() == null)
                        for (var row : oldRows.values()) if (row.voice() != null && (row.voice().id().equals(voice.voiceId())
                                || row.voice().id().equals(voice.targetId()))) notes.add(row.id());
                }
            }
            for (var outcomes : List.of(oldOutcomes.getOrDefault(id, List.of()), newOutcomes.getOrDefault(id, List.of())))
                for (var outcome : outcomes) {
                    notes.addAll(outcome.noteIds());
                    if (outcome.removedNotes() != null) outcome.removedNotes().forEach(note -> notes.add(note.id()));
                    if (outcome.resultNotes() != null) outcome.resultNotes().forEach(note -> notes.add(note.id()));
                }
            for (var rows : List.of(oldRows, newRows)) for (var note : notes) {
                var row = rows.get(note);
                if (row == null) continue;
                parts.add(row.part());
                scopes.add(new Scope(row.part(), range(row)));
            }
            Range span = null;
            for (var scope : scopes) span = join(span, scope.span);
            result.add(new Effect(branch, id, a, b, family, Set.copyOf(notes), Set.copyOf(parts), span,
                    List.copyOf(scopes), ordered));
        }
        return result;
    }

    /** Retain a longest in-order subsequence so moving one entry does not mark all shifted entries. */
    private static Set<String> moved(Map<String, JsonObject> before, Map<String, JsonObject> after) {
        var positions = new LinkedHashMap<String, Integer>();
        for (var id : before.keySet()) positions.put(id, positions.size());
        var common = after.keySet().stream().filter(positions::containsKey).toList();
        var previous = new int[common.size()];
        var tails = new int[common.size()];
        int length = 0;
        for (int i = 0; i < common.size(); i++) {
            int position = positions.get(common.get(i));
            int left = 0;
            int right = length;
            while (left < right) {
                int mid = (left + right) / 2;
                if (positions.get(common.get(tails[mid])) < position) left = mid + 1;
                else right = mid;
            }
            previous[i] = left == 0 ? -1 : tails[left - 1];
            tails[left] = i;
            if (left == length) length++;
        }
        var moved = new LinkedHashSet<>(common);
        for (int i = length == 0 ? -1 : tails[length - 1]; i >= 0; i = previous[i]) moved.remove(common.get(i));
        return moved;
    }

    private static Range point(ReconstructionEdits.Intent edit, ReconstructionEdits.Attack selector,
                               ReconstructionTimeMap clock) {
        double tolerance = ReconstructionEdits.Attack.TOLERANCE;
        return new Range(edit.toSource(Math.max(0, selector.at() - tolerance), clock),
                edit.toSource(selector.at() + tolerance, clock));
    }

    private static Range range(Row row) {
        var gate = row.effectiveTiming();
        return new Range(gate.sourceStart(), Math.max(gate.sourceEnd(), gate.sourceStart() + 1e-6));
    }

    private static Range join(Range a, Range b) {
        return a == null ? b : new Range(Math.min(a.start(), b.start()), Math.max(a.end(), b.end()));
    }

    private static String family(Kind kind) {
        return switch (kind) {
            case setRole, swapRoles, unmerge -> "role";
            case voiceEdit, noteVoicing -> "voice";
            case bend, glide -> "bend";
            case arpeggio, chordInstrument -> "figure";
            case insertNote, deleteNote, setNote, splitNote, retrigger, mergeNotes, transpose -> "note";
            default -> kind.name();
        };
    }

    static List<DirectConflict> direct(List<Effect> left, List<Effect> right) {
        var result = new ArrayList<DirectConflict>();
        for (var a : left) for (var b : right) {
            if (a.branch.equals(b.branch) || a.sameChange(b)) continue;
            var shared = new java.util.TreeSet<>(a.notes);
            shared.retainAll(b.notes);
            if (a.id.equals(b.id) || !shared.isEmpty() || a.family.equals(b.family) && intersects(a.parts, b.parts)
                    && a.span != null && b.span != null && a.span.overlaps(b.span)
                    && a.scopes.stream().anyMatch(x -> b.scopes.stream()
                            .anyMatch(y -> x.part == y.part && x.span.overlaps(y.span)))) {
                var reason = a.id.equals(b.id) ? "editId" : shared.isEmpty() ? "partSpanFamily" : "noteId";
                result.add(new DirectConflict(a.ref(), b.ref(), reason, List.copyOf(shared), a.span, b.span));
            }
        }
        return result;
    }

    static List<IndirectConflict> indirect(Path before, Path after, List<Effect> effects) throws IOException {
        if (effects.isEmpty() || before.equals(after)) return List.of();
        var touched = new LinkedHashSet<String>();
        effects.forEach(effect -> touched.addAll(effect.notes));
        var old = ReconstructionTrace.read(before);
        var updated = ReconstructionTrace.read(after);
        var pcm = pcm(before, after, old, updated, touched);
        var result = new ArrayList<IndirectConflict>();
        var refs = effects.stream().map(Effect::ref).distinct().toList();
        for (var id : union(old.keySet(), updated.keySet())) {
            if (touched.contains(id)) continue;
            var a = old.get(id);
            var b = updated.get(id);
            boolean allocation = !Objects.equals(placement(a), placement(b));
            boolean audio = pcm != null && (pcm.changed(a) || pcm.changed(b));
            if (allocation || audio) {
                var span = a == null ? range(b) : b == null ? range(a) : join(range(a), range(b));
                result.add(new IndirectConflict(id, span, allocation, audio, refs));
            }
        }
        return result;
    }

    private record Placement(String lane, Timing gate, int pitch) {}
    private static Placement placement(Row row) {
        return row == null || row.allocation() == null || row.allocation().placement() == null ? null
                : new Placement(lane(row), row.allocation().placement(), row.allocation().effective().note().pitch());
    }

    private static String lane(Row row) { return row.allocation() == null ? null : row.allocation().lane(); }

    private record Pcm(BitSet changed, float rate) {
        boolean changed(Row row) {
            var gate = renderedGate(row);
            if (gate == null) return false;
            int next = changed.nextSetBit(frame(gate.projectStart(), rate));
            return next >= 0 && next < frame(gate.projectEnd(), rate);
        }
    }

    private static Timing renderedGate(Row row) {
        return row != null && !row.voiced().isEmpty() ? row.effectiveTiming() : null;
    }

    private static int frame(double seconds, float rate) { return (int) Math.max(0, Math.ceil(seconds * rate)); }

    private static Pcm pcm(Path before, Path after, Map<String, Row> old, Map<String, Row> updated, Set<String> touched)
            throws IOException {
        var oldWave = before.resolve("render.wav");
        var newWave = after.resolve("render.wav");
        if (!Files.isRegularFile(oldWave) || !Files.isRegularFile(newWave)) return null;
        try (var a = AudioSystem.getAudioInputStream(oldWave.toFile());
             var b = AudioSystem.getAudioInputStream(newWave.toFile())) {
            var format = a.getFormat();
            if (!format.matches(b.getFormat()) || format.getFrameSize() <= 0) return null;
            float rate = format.getFrameRate();
            var excluded = new BitSet();
            for (var rows : List.of(old, updated)) for (var id : touched) {
                var gate = renderedGate(rows.get(id));
                if (gate != null) excluded.set(frame(gate.projectStart(), rate), frame(gate.projectEnd(), rate));
            }
            int frameSize = format.getFrameSize();
            byte[] x = new byte[4096 * frameSize];
            byte[] y = new byte[x.length];
            var changed = new BitSet();
            int frame = 0;
            while (true) {
                int n = a.readNBytes(x, 0, x.length);
                int m = b.readNBytes(y, 0, y.length);
                if (Math.max(n, m) == 0) break;
                for (int i = 0; i < Math.max(n, m); i += frameSize, frame++)
                    if (!excluded.get(frame) && (i >= Math.min(n, m)
                            || !Arrays.equals(x, i, i + frameSize, y, i, i + frameSize))) changed.set(frame);
            }
            return new Pcm(changed, rate);
        } catch (UnsupportedAudioFileException unsupported) {
            return null;
        }
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        var result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static <T> boolean intersects(Set<T> a, Set<T> b) { return a.stream().anyMatch(b::contains); }
}
