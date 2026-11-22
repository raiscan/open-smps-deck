package com.opensmpsdeck.headless;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        boolean off = json.has("modulationOff");
        require(!off || json.get("modulationOff").isJsonPrimitive()
                && json.getAsJsonPrimitive("modulationOff").isBoolean()
                && json.get("modulationOff").getAsBoolean(), at, "expected modulationOff:true");
        require(!off || !json.has("vibrato"), at, "choose vibrato or modulationOff");
        require(type != null || off || json.has("vibrato"), at, "expected a voicing change");
        vibrato(json, at);
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

    static Resolved resolve(List<ReconstructionEdits.Intent> edits, ReconstructionAllocation.Result allocation,
                            InstrumentCatalogue catalogue, Map<String, ReconstructionVoicePlan.Assignment> assignments,
                            Map<String, List<ReconstructionOverrides.Span>> scopes,
                            List<ReconstructionEdits.Outcome> outcomes) {
        if (edits.stream().noneMatch(edit -> edit.kind() == ReconstructionEdits.Kind.noteVoicing))
            return new Resolved(assignments, scopes);
        var entries = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var entry : allocation.notes()) entries.put(entry.note().id(), entry);
        var instruments = new LinkedHashMap<>(assignments);
        var modulation = new LinkedHashMap<String, ReconstructionOverrides.Span>();
        for (var edit : edits) {
            if (edit.kind() != ReconstructionEdits.Kind.noteVoicing) continue;
            var json = edit.json();
            var type = edit.laneType();
            var instrument = type == null ? null : new ReconstructionVoicePlan.Assignment(type, json.get("instrument").getAsString());
            var bend = vibrato(json, edit.id());
            boolean off = json.has("modulationOff");
            var hits = new ArrayList<String>();
            var misses = new ArrayList<String>();
            String issue = null;
            try {
                if (instrument != null) instrument.validate(catalogue, edit.id());
                for (String id : edit.noteIds()) {
                    var entry = entries.get(id);
                    String reason = entry == null ? "note has no current allocation"
                            : entry.lane() == null ? java.util.Objects.toString(entry.reason(), "note has no allocated lane")
                            : instrument != null && !type.accepts(entry) ? "instrument cannot play on " + entry.lane()
                            : (off || bend != null) && !entry.lane().startsWith("FM") ? "vibrato requires FM"
                            : null;
                    if (reason == null) hits.add(id);
                    else {
                        misses.add(id);
                        outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), ReconstructionEdits.Status.orphaned,
                                List.of(), List.of(id), id + ": " + reason, null));
                    }
                }
                for (String id : hits) {
                    if (instrument != null) instruments.put(id, instrument);
                    if (off || bend != null) {
                        var entry = entries.get(id);
                        var settings = new ReconstructionOverrides.Settings(null, null, null, null, List.of(id),
                                null, null, null, null, bend, off);
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
