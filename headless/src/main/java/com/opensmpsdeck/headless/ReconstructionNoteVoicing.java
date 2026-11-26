package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.headless.ReconstructionEditing.VibratoSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionEdits.integer;
import static com.opensmpsdeck.headless.ReconstructionEdits.string;

/** Resolve authored note scopes against the current allocation on every assembly. */
final class ReconstructionNoteVoicing {
    private ReconstructionNoteVoicing() {}

    record Resolved(Map<String, ReconstructionVoicePlan.Assignment> instruments,
                    Map<String, List<ReconstructionOverrides.Span>> scopes) {}

    static void validate(JsonObject json, ReconstructionLaneType type, String at) {
        require(json.has("instrument") == (type != null), at, "instrument requires laneType");
        if (type != null) string(json, "instrument", at);
        // A second patch at the lane's gain can be much louder or quieter than the first.
        require(!json.has("gain") || type != null, at, "gain requires instrument");
        if (json.has("gain")) {
            double gain = ReconstructionEdits.number(json.get("gain"), at + ".gain");
            require(gain > 0 && gain <= 4, at + ".gain", "expected a lane gain above 0 and up to 4");
        }
        boolean off = json.has("modulationOff");
        require(!off || json.get("modulationOff").isJsonPrimitive()
                && json.getAsJsonPrimitive("modulationOff").isBoolean()
                && json.get("modulationOff").getAsBoolean(), at, "expected modulationOff:true");
        int modulationFields = (off ? 1 : 0) + (json.has("vibrato") ? 1 : 0) + (json.has("vibratoEnvelope") ? 1 : 0);
        require(modulationFields <= 1, at, "choose vibrato, vibratoEnvelope or modulationOff");
        require(type != null || modulationFields == 1, at, "expected a voicing change");
        vibrato(json, at);
        envelope(json, at);
    }

    private static ReconstructionVoicing.Vibrato vibrato(JsonObject json, String at) {
        if (!json.has("vibrato")) return null;
        require(json.get("vibrato").isJsonObject(), at, "expected a vibrato object");
        var value = json.getAsJsonObject("vibrato");
        var delay = integer(value, "delay", at, 1, 255);
        var rate = integer(value, "rate", at, 1, 255);
        var delta = integer(value, "delta", at, -128, 127);
        var steps = integer(value, "steps", at, 2, 255);
        require(value.size() == 4 && delay != null && rate != null && delta != null && steps != null,
                at, "expected vibrato delay, rate, delta and steps");
        return new ReconstructionVoicing.Vibrato(delay, rate, delta, steps);
    }

    static List<VibratoSegment> envelope(JsonObject json, String at) {
        if (!json.has("vibratoEnvelope")) return null;
        at += ".vibratoEnvelope";
        require(json.get("vibratoEnvelope").isJsonArray(), at, "expected an array of segments");
        var values = json.getAsJsonArray("vibratoEnvelope");
        require(!values.isEmpty(), at, "expected at least one segment");
        var segments = new ArrayList<VibratoSegment>();
        int previous = -1;
        for (int i = 0; i < values.size(); i++) {
            String field = at + "[" + i + "]";
            require(values.get(i).isJsonObject(), field, "expected a segment object");
            var value = values.get(i).getAsJsonObject();
            var offset = integer(value, "startOffset", field, 0, Integer.MAX_VALUE);
            double depth = ReconstructionEdits.number(value.get("depthCents"), field + ".depthCents");
            double rate = value.has("rateHz") ? ReconstructionEdits.number(value.get("rateHz"), field + ".rateHz") : 0;
            require(offset != null && offset > previous, field, "startOffset must strictly increase");
            require(depth >= 0 && depth <= 200, field, "expected depthCents in 0–200");
            require(rate >= 0 && rate <= 15 && (depth == 0 || rate >= .12), field,
                    "expected rateHz in 0.12–15 for a moving segment");
            require(value.size() == (value.has("rateHz") ? 3 : 2), field, "unexpected segment field");
            segments.add(new VibratoSegment(offset, rate, depth));
            previous = offset;
        }
        return List.copyOf(segments);
    }

    static Set<String> retriggerAnchors(ReconstructionStages.Score score) {
        return score.retriggers() == null ? Set.of() : score.retriggers().stream()
                .map(ReconstructionRetriggers.Burst::anchor).collect(java.util.stream.Collectors.toSet());
    }

    static Resolved resolve(List<ReconstructionEdits.Intent> edits, ReconstructionAllocation.Result allocation,
                            InstrumentCatalogue catalogue, ReconstructionStages.Score score,
                            Map<String, ReconstructionVoicePlan.Assignment> assignments,
                            Map<String, List<ReconstructionOverrides.Span>> scopes,
                            List<ReconstructionEdits.Outcome> outcomes) {
        if (edits.stream().noneMatch(edit -> edit.kind() == ReconstructionEdits.Kind.noteVoicing))
            return new Resolved(assignments, scopes);
        var retriggers = retriggerAnchors(score);
        var entries = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var entry : allocation.notes()) entries.put(entry.note().id(), entry);
        var instruments = new LinkedHashMap<>(assignments);
        var modulation = new LinkedHashMap<String, ReconstructionOverrides.Span>();
        for (var edit : edits) {
            if (edit.kind() != ReconstructionEdits.Kind.noteVoicing) continue;
            var json = edit.json();
            var type = edit.laneType();
            var instrument = type == null ? null : new ReconstructionVoicePlan.Assignment(type, json.get("instrument").getAsString());
            Double gain = json.has("gain") ? json.get("gain").getAsDouble() : null;
            var bend = vibrato(json, edit.id());
            var envelope = envelope(json, edit.id());
            boolean off = json.has("modulationOff");
            var hits = new ArrayList<String>();
            var misses = new ArrayList<String>();
            String issue = null;
            try {
                require(envelope == null || score.profile().game().s3kRules(), edit.id(), "vibratoEnvelope requires the S3K target");
                if (instrument != null) instrument.validate(catalogue, edit.id());
                for (String id : edit.noteIds()) {
                    var entry = entries.get(id);
                    String reason = entry == null ? "note has no current allocation"
                            : entry.lane() == null ? java.util.Objects.toString(entry.reason(), "note has no allocated lane")
                            : instrument != null && !type.accepts(entry) ? "instrument cannot play on " + entry.lane()
                            : (off || bend != null || envelope != null) && !entry.lane().startsWith("FM") ? "vibrato requires FM"
                            : envelope != null && (entry.note().run() != null || allocation.chords().containsKey(id)
                                    || retriggers.contains(id)) ? "vibratoEnvelope requires a single attack"
                            : envelope != null && envelope.getLast().startOffset() >= entry.endTick() - entry.startTick()
                                    ? "segment starts outside the allocated note"
                            : null;
                    if (reason == null) hits.add(id);
                    else {
                        misses.add(id);
                        outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), ReconstructionEdits.Status.orphaned,
                                List.of(), List.of(id), id + ": " + reason, null));
                    }
                    if (reason == null && envelope != null)
                        require(entry.note().bend() == null || entry.note().bend().editReason() == null,
                                edit.id(), "vibratoEnvelope and an authored bend cannot share note " + id);
                }
                for (String id : hits) {
                    if (instrument != null) instruments.put(id, instrument);
                    if (off || bend != null || envelope != null || gain != null) {
                        var entry = entries.get(id);
                        var settings = new ReconstructionOverrides.Settings(null, null, null, null, List.of(id),
                                null, gain, null, null, bend, off, envelope);
                        // A gain and a modulation edit on one note both apply. Otherwise the later edit replaces.
                        var earlier = modulation.get(id);
                        if (earlier != null && (gain == null) != (earlier.settings().gain() == null)) {
                            var both = settings.combine(earlier.settings());
                            settings = new ReconstructionOverrides.Settings(null, null, null, null, List.of(id), null,
                                    both.gain(), null, null, both.vibrato(), both.modulationOff(), both.vibratoEnvelope());
                        }
                        modulation.put(id, new ReconstructionOverrides.Span(entry.startTick(), entry.endTick(), settings, -1));
                    }
                }
            } catch (IllegalArgumentException invalid) {
                issue = invalid.getMessage();
                hits.clear();
            }
            if (hits.isEmpty() && issue == null && !misses.isEmpty()) continue;
            var status = issue != null ? ReconstructionEdits.Status.invalid : hits.isEmpty()
                    ? ReconstructionEdits.Status.orphaned : ReconstructionEdits.Status.applied;
            outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), status, List.copyOf(hits),
                    misses.isEmpty() ? null : List.copyOf(misses),
                    issue != null ? issue : misses.isEmpty() ? null : "Some notes have no current allocation", null));
        }
        var resolved = new HashMap<>(scopes);
        modulation.forEach((id, span) -> {
            var spans = new ArrayList<ReconstructionOverrides.Span>();
            spans.add(span); // Authored note modulation takes precedence over lane ranges.
            spans.addAll(scopes.getOrDefault(id, List.of()));
            resolved.put(id, List.copyOf(spans));
        });
        return new Resolved(instruments, resolved);
    }
}
