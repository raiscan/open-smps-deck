package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Resolves editable scopes against the allocated ticks of each individual note. */
final class ReconstructionOverrides {
    record Settings(@Nullable Double startSeconds, @Nullable Double endSeconds,
                    @Nullable Integer startTick, @Nullable Integer endTick, @Nullable List<String> noteIds,
                    @Nullable String variantId, @Nullable Double gain, @Nullable Double gate,
                    @Nullable Integer minimumReleaseRate, @Nullable ReconstructionVoicing.Vibrato vibrato,
                    @Nullable Boolean modulationOff,
                    @Nullable List<ReconstructionEditing.VibratoSegment> vibratoEnvelope) {
        Settings(Double startSeconds, Double endSeconds, Integer startTick, Integer endTick, List<String> noteIds,
                 String variantId, Double gain, Double gate, Integer minimumReleaseRate,
                 ReconstructionVoicing.Vibrato vibrato, Boolean modulationOff) {
            this(startSeconds, endSeconds, startTick, endTick, noteIds, variantId, gain, gate, minimumReleaseRate,
                    vibrato, modulationOff, null);
        }

        boolean changesModulation() {
            return vibratoEnvelope != null || vibrato != null || Boolean.TRUE.equals(modulationOff);
        }

        boolean conflicts(Settings other) {
            return variantId != null && other.variantId != null || gain != null && other.gain != null
                    || gate != null && other.gate != null || minimumReleaseRate != null && other.minimumReleaseRate != null
                    || changesModulation() && other.changesModulation();
        }

        Settings combine(Settings other) {
            return new Settings(null, null, null, null, null,
                    variantId == null ? other.variantId : variantId, gain == null ? other.gain : gain,
                    gate == null ? other.gate : gate,
                    minimumReleaseRate == null ? other.minimumReleaseRate : minimumReleaseRate,
                    changesModulation() ? vibrato : other.vibrato,
                    changesModulation() ? modulationOff : other.modulationOff,
                    changesModulation() ? vibratoEnvelope : other.vibratoEnvelope);
        }
    }

    record Span(int start, int end, Settings settings, int restoreTick) {}

    private ReconstructionOverrides() {}

    static Map<String, List<Span>> resolve(String lane, List<Settings> scopes,
                                           List<ReconstructionAllocation.Entry> notes, int frames,
                                           InstrumentCatalogue catalogue) {
        return resolve(lane, scopes, notes, new ReconstructionTimeMap(null, null, frames / 60.0), catalogue);
    }

    static Map<String, List<Span>> resolve(String lane, List<Settings> scopes,
                                           List<ReconstructionAllocation.Entry> notes, ReconstructionTimeMap timeline,
                                           InstrumentCatalogue catalogue) {
        if (scopes == null || scopes.isEmpty()) return Map.of();
        int frames = timeline.ticks();
        var targets = new HashMap<String, ReconstructionAllocation.Entry>();
        for (var note : notes) targets.put(note.note().id(), note);
        var result = new HashMap<String, List<Span>>();
        var timeRanges = new ArrayList<Span>();
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
                                && scope.startSeconds >= 0 && scope.endSeconds <= timeline.frames() / 60.0,
                        at, "expected startSeconds and endSeconds within the project");
                start = Math.min(frames, timeline.tick(scope.startSeconds));
                end = Math.min(frames, timeline.tick(scope.endSeconds));
            }
            require(start >= 0 && end <= frames && end > start, at, "expected a nonempty range within the project");
            if (scope.noteIds == null) {
                for (var prior : timeRanges)
                    require(end <= prior.start || start >= prior.end || !scope.conflicts(prior.settings),
                            at, "overlapping time ranges set the same field");
                timeRanges.add(new Span(start, end, scope, -1));
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
            require(scope.vibratoEnvelope == null, at, "use a noteVoicing edit for vibratoEnvelope");
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
                    require(from >= prior.end || to <= prior.start || !scope.conflicts(prior.settings),
                            at, "overlap on note " + note.note().id() + " sets the same field");
                spans.add(new Span(from, to, scope, scope.noteIds == null && end < frames ? end : -1));
                matched = true;
            }
            require(matched, at, "scope matches no allocated notes");
        }
        result.values().forEach(spans -> spans.sort(Comparator.comparingInt(Span::start)));
        return result;
    }

    static NavigableMap<Integer, Settings> changes(List<Span> spans, int voicedEnd) {
        var activeSpans = spans.stream().filter(span -> span.start < voicedEnd).toList();
        var boundaries = new TreeSet<Integer>();
        for (var span : activeSpans) {
            boundaries.add(span.start);
            int end = activeEnd(span, voicedEnd);
            if (end != Integer.MAX_VALUE) boundaries.add(end);
        }
        var changes = new TreeMap<Integer, Settings>();
        for (int boundary : boundaries) {
            Settings current = null;
            for (var span : activeSpans) {
                if (span.start <= boundary && boundary < activeEnd(span, voicedEnd))
                    current = current == null ? span.settings : current.combine(span.settings);
            }
            changes.put(boundary, current);
        }
        return changes;
    }

    private static int activeEnd(Span span, int voicedEnd) {
        if (span.end < voicedEnd) return span.end;
        return span.restoreTick < 0 ? Integer.MAX_VALUE : span.restoreTick;
    }
}
