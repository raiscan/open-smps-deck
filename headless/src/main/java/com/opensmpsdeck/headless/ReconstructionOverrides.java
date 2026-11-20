package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Resolves editable scopes against the allocated ticks of each individual note. */
final class ReconstructionOverrides {
    record Settings(@Nullable Double startSeconds, @Nullable Double endSeconds,
                    @Nullable Integer startTick, @Nullable Integer endTick, @Nullable List<String> noteIds,
                    @Nullable String variantId, @Nullable Double gain, @Nullable Double gate,
                    @Nullable Integer minimumReleaseRate, @Nullable ReconstructionVoicing.Vibrato vibrato,
                    @Nullable Boolean modulationOff) {
        boolean changesModulation() { return vibrato != null || Boolean.TRUE.equals(modulationOff); }
    }

    record Span(int start, int end, Settings settings, int restoreTick) {}

    private ReconstructionOverrides() {}

    static int tick(double seconds) {
        return (int) Math.round(seconds * S3kRenderer.PRESENTATIONS_PER_SECOND);
    }

    static Map<String, List<Span>> resolve(String lane, List<Settings> scopes,
                                           List<ReconstructionAllocation.Entry> notes, int frames,
                                           InstrumentCatalogue catalogue) {
        if (scopes == null || scopes.isEmpty()) return Map.of();
        var targets = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var note : notes) targets.put(note.note().id(), note);
        var result = new HashMap<String, List<Span>>();
        var timeRanges = new java.util.TreeMap<Integer, Integer>();
        for (int i = 0; i < scopes.size(); i++) {
            var scope = scopes.get(i);
            String at = "voicing.json.lanes." + lane + ".overrides[" + i + "]";
            boolean ticks = scope.startTick != null || scope.endTick != null;
            boolean seconds = scope.startSeconds != null || scope.endSeconds != null;
            require(!ticks || !seconds, at, "use ticks or project seconds");
            require(ticks || seconds || scope.noteIds != null, at, "expected a range or noteIds");
            int start = 0, end = frames;
            if (ticks) {
                require(scope.startTick != null && scope.endTick != null, at, "expected startTick and endTick");
                start = scope.startTick;
                end = scope.endTick;
            }
            if (seconds) {
                require(scope.startSeconds != null && scope.endSeconds != null
                                && scope.startSeconds >= 0 && scope.endSeconds <= frames / 60.0,
                        at, "expected startSeconds and endSeconds within the project");
                start = tick(scope.startSeconds);
                end = tick(scope.endSeconds);
            }
            require(start >= 0 && end <= frames && end > start, at, "expected a nonempty range within the project");
            if (scope.noteIds == null) {
                var before = timeRanges.floorEntry(start);
                var after = timeRanges.ceilingEntry(start);
                require((before == null || before.getValue() <= start) && (after == null || after.getKey() >= end),
                        at, "overlapping time ranges");
                timeRanges.put(start, end);
            }
            require(!lane.equals("DAC"), at, "scoped voicing requires an FM or PSG lane");
            boolean fm = lane.startsWith("FM");
            if (scope.variantId != null) ReconstructionVoicing.kind(catalogue, scope.variantId,
                    fm ? InstrumentCatalogue.Kind.FM_VOICE : InstrumentCatalogue.Kind.PSG_VOLUME, at + ".variantId");
            require(scope.gain == null || scope.gain >= 0 && scope.gain <= 4, at + ".gain", "expected 0–4");
            require(scope.gate == null || scope.gate > 0 && scope.gate <= 1, at + ".gate", "expected (0,1]");
            require(scope.minimumReleaseRate == null || fm && scope.minimumReleaseRate >= 0 && scope.minimumReleaseRate <= 15,
                    at + ".minimumReleaseRate", "expected an FM release rate in 0–15");
            require(scope.vibrato == null || !Boolean.TRUE.equals(scope.modulationOff),
                    at, "choose vibrato or modulationOff");
            ReconstructionVoicing.vibrato(scope.vibrato, at + ".vibrato");
            require(scope.variantId != null || scope.gain != null || scope.gate != null
                    || scope.minimumReleaseRate != null || scope.changesModulation(), at, "expected a voicing change");

            var selected = targets.values();
            if (scope.noteIds != null) {
                var ids = new HashSet<>(scope.noteIds);
                require(!ids.isEmpty() && ids.size() == scope.noteIds.size(), at + ".noteIds", "expected distinct note IDs");
                for (String id : ids) require(targets.containsKey(id), at + ".noteIds", "note " + id + " is not on " + lane);
                selected = ids.stream().map(targets::get).toList();
            }
            boolean matched = false;
            for (var note : selected) {
                int from = Math.max(start, note.startTick()), to = Math.min(end, note.endTick());
                if (from >= to) {
                    require(scope.noteIds == null, at + ".noteIds", "note " + note.note().id() + " is outside the range");
                    continue;
                }
                require(fm || note.role() != ReconstructionRoles.Role.drums, at, "PSG noise percussion does not support scoped voicing");
                var spans = result.computeIfAbsent(note.note().id(), ignored -> new ArrayList<>());
                for (var prior : spans)
                    require(from >= prior.end || to <= prior.start, at, "overlap on note " + note.note().id());
                spans.add(new Span(from, to, scope, scope.noteIds == null && end < frames ? end : -1));
                matched = true;
            }
            require(matched, at, "scope matches no allocated notes");
        }
        result.values().forEach(spans -> spans.sort(Comparator.comparingInt(Span::start)));
        return result;
    }
}
