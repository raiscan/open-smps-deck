package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionEdits.*;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Authored copies are resolved against the current source notes on every assembly. */
public final class ReconstructionLayers {
    public static final double DB_PER_TL = 0.75;
    public static final int ECHO_REFERENCE_TL = 7;
    public static final int DEFAULT_ECHO_DELAY_FRAMES = 14;
    public static final int DEFAULT_ECHO_ATTENUATION = 11;

    enum Mode { echo, chorus, doubleNote, off }

    record Decay(int frame, int attenuation) {}

    record Settings(Mode mode, ReconstructionLaneType laneType, int delayFrames, int attenuation,
                    int detune, int octave, List<Decay> decay, Set<ReconstructionLanePolicies.Kind> policies) {
        boolean overridesAll() { return policies.isEmpty(); }

        boolean masks(ReconstructionLanePolicies.Kind policy) {
            return overridesAll() || policies.contains(policy);
        }
    }

    record Request(Intent edit, Settings settings) {}

    public enum Status { applied, off, skipped }

    public enum CurveStatus { exact, approximated }

    public record Curve(CurveStatus status, double rmseCents, boolean withinTolerance) {
        static Curve measured(double rmseCents) {
            return new Curve(rmseCents < .01 ? CurveStatus.exact : CurveStatus.approximated,
                    rmseCents, rmseCents <= ReconstructionLayerPitch.TOLERANCE_CENTS);
        }

        String summary() {
            return String.format(java.util.Locale.ROOT, "%s pitch curve (%.2f cents RMS%s)",
                    status, rmseCents, withinTolerance ? "" : ", exceeds 15 cents");
        }
    }

    public record Result(String noteId, String editId, String mode, @Nullable ReconstructionLaneType laneType,
                         int delayFrames, int attenuation, Status status, @Nullable String reason,
                         @Nullable String lane, @Nullable Integer pitch,
                         @Nullable Integer startTick, @Nullable Integer endTick, @Nullable Curve curve) {
        static Result of(String id, Request request, Status status, String reason,
                         String lane, Integer pitch, Integer start, Integer end) {
            return of(id, request, status, reason, lane, pitch, start, end, null);
        }

        static Result of(String id, Request request, Status status, String reason,
                         String lane, Integer pitch, Integer start, Integer end, Curve curve) {
            var settings = request.settings();
            return new Result(id, request.edit().id(), modeName(settings.mode()), settings.laneType(),
                    settings.delayFrames(), settings.attenuation(), status, reason, lane, pitch, start, end, curve);
        }

        void report(List<Outcome> outcomes) {
            var editStatus = switch (status) {
                case applied -> ReconstructionEdits.Status.applied;
                case off -> ReconstructionEdits.Status.satisfied;
                case skipped -> ReconstructionEdits.Status.conflict;
            };
            outcomes.add(new Outcome(editId, Kind.layer, editStatus, List.of(noteId), null, reason,
                    null, null, null, laneType, lane, this));
        }
    }

    private ReconstructionLayers() {}

    static String modeName(Mode mode) {
        return mode == Mode.doubleNote ? "double" : mode.name();
    }

    static Settings settings(JsonObject json, ReconstructionLaneType type, String at) {
        var mode = switch (string(json, "mode", at)) {
            case "echo" -> Mode.echo;
            case "chorus" -> Mode.chorus;
            case "double" -> Mode.doubleNote;
            case "off" -> Mode.off;
            default -> throw new IllegalArgumentException(at + ".mode: expected echo, chorus, double or off");
        };
        var delay = integer(json, "delayFrames", at, 1, 600);
        var attenuation = integer(json, "attenuation", at, 0, 127);
        var db = json.has("levelDb") ? number(json.get("levelDb"), at + ".levelDb") : null;
        var detune = integer(json, "detune", at, -128, 127);
        var octave = integer(json, "octave", at, -1, 1);
        require(mode == Mode.off ? type == null : type == ReconstructionLaneType.FM || type == ReconstructionLaneType.PSG,
                at + ".laneType", "use FM or PSG for a copy and omit the target for off");
        require(mode == Mode.echo || delay == null && !json.has("volumeDecay"), at, "delayFrames and volumeDecay require echo");
        require(mode == Mode.chorus || detune == null, at, "detune requires chorus");
        require(mode == Mode.doubleNote || octave == null, at, "octave requires double");
        require(mode != Mode.off || attenuation == null && db == null, at, "off has no level");
        require(attenuation == null || db == null, at, "choose attenuation or levelDb");
        require(db == null || db >= -127 * DB_PER_TL && db <= 0, at + ".levelDb", "expected -95.25 to 0 dB");
        int level = attenuation != null ? attenuation : db != null ? (int) Math.round(-db / DB_PER_TL)
                : switch (mode) {
                    case off -> 0;
                    case echo -> DEFAULT_ECHO_ATTENUATION;
                    case chorus, doubleNote -> 12;
                };
        var decay = new ArrayList<Decay>();
        if (json.has("volumeDecay")) {
            int previousFrame = 0, previousAttenuation = level;
            for (var value : array(json, "volumeDecay", at)) {
                String where = at + ".volumeDecay[" + decay.size() + "]";
                require(value.isJsonObject(), where, "expected frame and attenuation");
                var step = value.getAsJsonObject();
                fields(step, where, Set.of("frame", "attenuation"));
                var frame = integer(step, "frame", where, previousFrame + 1, 600);
                var next = integer(step, "attenuation", where, previousAttenuation, 127);
                require(frame != null && next != null, where, "frame and attenuation are required");
                decay.add(new Decay(frame, next));
                previousFrame = frame;
                previousAttenuation = next;
            }
        }
        int delayFrames = mode == Mode.echo ? (delay == null ? DEFAULT_ECHO_DELAY_FRAMES : delay) : 0;
        var policies = new LinkedHashSet<ReconstructionLanePolicies.Kind>();
        if (json.has("policies")) {
            require(mode == Mode.off, at + ".policies", "policy masks require off");
            var values = array(json, "policies", at);
            require(!values.isEmpty(), at + ".policies", "expected one or more policy names");
            for (var value : values) {
                var name = new JsonObject();
                name.add("policy", value);
                require(policies.add(choice(ReconstructionLanePolicies.Kind.class, name, "policy", at)),
                        at + ".policies", "duplicate policy name");
            }
        }
        return new Settings(mode, type, delayFrames, level,
                mode == Mode.chorus ? (detune == null ? -3 : detune) : 0, octave == null ? 0 : octave,
                List.copyOf(decay), java.util.Collections.unmodifiableSet(policies));
    }

    static boolean present(ReconstructionEdits edits) {
        return edits.intents().stream().anyMatch(intent -> intent.kind() == Kind.layer);
    }

    static Map<String, Request> resolve(Path work, ReconstructionStages.Score score,
                                        ReconstructionStructure.Result structure, ReconstructionAllocation.Result allocation,
                                        ReconstructionEdits edits, List<Outcome> outcomes) throws IOException {
        if (!present(edits)) return Map.of();
        var clock = ReconstructionTimeMap.of(score);
        var notes = new LinkedHashMap<String, ReconstructionStages.Note>();
        for (var note : score.notes()) notes.put(note.id(), note);
        var roles = new LinkedHashMap<String, ReconstructionRoles.Role>();
        for (var entry : allocation.notes()) {
            var note = entry.note();
            if (allocation.timeBase() == ReconstructionTimeMap.TimeBase.project) note = clock.sourceNote(note);
            notes.putIfAbsent(note.id(), note);
            roles.put(entry.note().id(), entry.role());
        }

        var selected = new LinkedHashMap<String, Request>();
        for (var edit : edits.bindSections(work, score, structure, ReconstructionStages.Stage.ASSEMBLE, outcomes).intents()) {
            if (edit.kind() != Kind.layer) continue;
            var request = new Request(edit, settings(edit.json(), edit.laneType(), edit.id()));
            var ids = new LinkedHashSet<>(edit.noteIds());
            if (edit.span() != null) {
                notes.values().stream().filter(note -> edit.matchesSpan(note, edit.inBase(note.start(), clock)))
                        .filter(note -> edit.fromRole() == null || edit.fromRole() == roles.get(note.id()))
                        .map(ReconstructionStages.Note::id).forEach(ids::add);
                if (ids.isEmpty())
                    outcomes.add(new Outcome(edit.id(), Kind.layer, ReconstructionEdits.Status.orphaned,
                            List.of(), null, "No notes match this layer span", null));
            }
            var points = edit.attack() == null ? edit.selectors() : List.of(edit.attack());
            for (var point : points) {
                var matches = notes.values().stream().filter(note -> point.matches(note, edit.inBase(note.start(), clock)))
                        .filter(note -> edit.fromRole() == null || edit.fromRole() == roles.get(note.id()))
                        .map(ReconstructionStages.Note::id).toList();
                if (matches.size() == 1) ids.add(matches.getFirst());
                else outcomes.add(invalidAttack(edit, matches));
            }
            for (String id : ids) {
                if (!notes.containsKey(id)) {
                    outcomes.add(new Outcome(edit.id(), Kind.layer, ReconstructionEdits.Status.orphaned,
                            List.of(), List.of(id), "No current note with that ID", null));
                    continue;
                }
                if (edit.fromRole() != null && edit.fromRole() != roles.get(id)) {
                    outcomes.add(new Outcome(edit.id(), Kind.layer, ReconstructionEdits.Status.orphaned,
                            List.of(), List.of(id), "Note does not match fromRole", null));
                    continue;
                }
                var prior = selected.put(id, request);
                if (prior != null) outcomes.add(new Outcome(prior.edit().id(), Kind.layer, ReconstructionEdits.Status.satisfied,
                        List.of(id), null, "Superseded by layer edit " + edit.id(), null));
            }
        }
        return selected;
    }
}
