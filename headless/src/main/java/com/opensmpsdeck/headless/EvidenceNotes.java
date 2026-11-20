package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;
import static com.opensmpsdeck.headless.ReconstructionView.*;

final class EvidenceNotes {
    record PatchChange(String lane, double time, String name) {}

    private EvidenceNotes() {}

    static List<DrawnNote> collect(Path work, Span span, Options options, Map<String, ReconstructionTrace.Row> rows,
                                   boolean hasVoicing, StudioProject project) throws IOException {
        var notes = new ArrayList<DrawnNote>();
        for (var row : rows.values()) {
            if (!options.parts().isEmpty() && !options.parts().contains(row.part())) continue;
            var allocation = row.allocation();
            String lane = allocation == null ? null : allocation.lane();
            boolean sourceSelected = options.lanes().isEmpty() || options.lanes().contains(lane == null ? "" : lane)
                    || row.voiced().stream().anyMatch(gate -> options.lanes().contains(gate.lane()))
                    || lane == null && allocation != null
                    && allocation.expectedLanes().stream().anyMatch(options.lanes()::contains);
            var origin = row.source();
            if (sourceSelected && origin != null) {
                var own = origin.note();
                var layer = origin.transcriber() != null || "external".equals(origin.provenance())
                        ? Layer.external : switch (origin.provenance()) {
                            case "midi", "audio-refine" -> Layer.suno;
                            default -> Layer.trace;
                        };
                String evidence = origin.transcriber() == null ? origin.provenance() : origin.transcriber().tool();
                add(notes, span, row.id(), layer, row.part(), lane, own.note().pitch(), own.timing(),
                        row.status(), evidence);
            } else if (sourceSelected && (row.status() == ReconstructionTrace.Status.dropped
                    || row.status() == ReconstructionTrace.Status.merged)) {
                var own = row.score();
                add(notes, span, row.id(), Layer.trace, row.part(), lane, own == null ? row.pitch() : own.note().pitch(),
                        own == null ? row.timing() : own.timing(), row.status(), "trace");
            }
            for (var gate : row.voiced()) {
                if (options.lanes().isEmpty() || options.lanes().contains(gate.lane()))
                    add(notes, span, gate.id(), Layer.lane, row.part(), gate.lane(), gate.pitch(), gate.gate(),
                            row.status(), gate.evidence());
            }
            if (!hasVoicing && lane != null && (options.lanes().isEmpty() || options.lanes().contains(lane))) {
                var own = allocation.effective();
                add(notes, span, row.id(), Layer.lane, row.part(), lane, own == null ? row.pitch() : own.note().pitch(),
                        allocation.placement(), row.status(), "allocated");
            }
        }
        addInsertions(work, span, options, rows, notes);
        if (options.parts().isEmpty() && rows.values().stream().flatMap(row -> row.voiced().stream())
                .anyMatch(gate -> "assembled".equals(gate.evidence()))) {
            addAssemblyOnlyNotes(project, span, options.lanes(), notes);
        }
        return labels(notes, span);
    }

    private static void addAssemblyOnlyNotes(StudioProject project, Span span, List<String> lanes,
                                             List<DrawnNote> notes) {
        if (project == null || project.nativeProgram() != null || project.tempo() != 0) return;
        var covered = new HashMap<String, java.util.NavigableMap<Long, Long>>();
        for (var note : notes) if (note.layer() == Layer.lane)
            covered.computeIfAbsent(note.lane(), ignored -> new java.util.TreeMap<>())
                    .merge(note.timing().startTick(), note.timing().endTick(), Math::max);
        var sequences = sequences(project);
        double startTick = span.seconds(span.start(), TimeBase.project) * 60;
        double endTick = span.seconds(span.end(), TimeBase.project) * 60;
        for (var clip : GroupArrangement.flatten(project)) {
            if (clip.endTick() <= startTick || clip.startTick() >= endTick || clip.lane().equals("DAC")
                    || !lanes.isEmpty() && !lanes.contains(clip.lane())) continue;
            var source = sequences.get(clip.sequenceId());
            var lane = covered.get(clip.lane());
            int period = source.lengthTicks();
            for (int offset = 0; offset < clip.lengthTicks() && clip.startTick() + offset < endTick; offset += period) {
                for (var event : source.notes()) {
                    int start = clip.startTick() + offset + event.tick();
                    int end = Math.min(clip.endTick(), start + event.duration());
                    if (start >= end || end <= startTick || start >= endTick) continue;
                    var gate = lane == null ? null : lane.floorEntry((long) start);
                    // Includes linked placements and echo/copy events. A bend can
                    // have several native events under one trace gate.
                    if (gate != null && gate.getValue() > start) continue;
                    String id = clip.id() + "/" + event.id() + "@" + start;
                    add(notes, span, id, Layer.lane, null, clip.lane(), event.pitch(),
                            ReconstructionTrace.ticks(start, end, span.timeline()), null, "assembly-only");
                }
            }
        }
    }

    private static void add(List<DrawnNote> notes, Span span, String id, Layer layer, Integer part, String lane,
                            int pitch, ReconstructionTrace.Timing timing, ReconstructionTrace.Status status, String evidence) {
        if (timing != null && span.overlaps(timing))
            notes.add(new DrawnNote(id, null, layer, part, lane, pitch, ReconstructionDescribe.pitch(pitch),
                    timing, status, evidence));
    }

    private static void addInsertions(Path work, Span span, Options options, Map<String, ReconstructionTrace.Row> rows,
                                      List<DrawnNote> notes) throws IOException {
        Path file = work.resolve("edits.json");
        if (Files.notExists(file)) return;
        var edits = ReconstructionArtifacts.readTree(file).getAsJsonObject().getAsJsonArray("edits");
        if (edits == null) return;
        for (var entry : edits) {
            var edit = entry.getAsJsonObject();
            if (!edit.has("op") || !edit.get("op").getAsString().equals("insertNote")) continue;
            String id = "edit-" + edit.get("id").getAsString();
            int part = edit.get("part").getAsInt();
            if (!options.parts().isEmpty() && !options.parts().contains(part)) continue;
            var row = rows.get(id);
            String lane = row == null || row.allocation() == null ? null : row.allocation().lane();
            if (lane == null && edit.has("lane")) lane = edit.get("lane").getAsString();
            if (!options.lanes().isEmpty() && (lane == null || !options.lanes().contains(lane))) continue;
            notes.removeIf(note -> note.id().equals(id) && note.layer() == Layer.trace);
            int pitch = edit.get("pitch").getAsInt();
            var note = new ReconstructionStages.Note(id, part, edit.get("start").getAsDouble(),
                    edit.get("end").getAsDouble(), pitch, 96, "edit", 1);
            add(notes, span, id, Layer.edit, part, lane, pitch,
                    ReconstructionTrace.at(note, TimeBase.source, span.timeline()).timing(),
                    row == null ? null : row.status(), row == null ? "pending" : "edit");
        }
    }

    private static List<DrawnNote> labels(List<DrawnNote> notes, Span span) {
        notes.sort(Comparator.comparingDouble((DrawnNote note) -> span.start(note.timing()))
                .thenComparingInt(DrawnNote::pitch).thenComparing(DrawnNote::id).thenComparing(DrawnNote::layer));
        var labels = new HashMap<String, String>();
        return notes.stream().map(note -> new DrawnNote(note.id(),
                labels.computeIfAbsent(note.id(), ignored -> "n" + (labels.size() + 1)), note.layer(), note.part(),
                note.lane(), note.pitch(), note.pitchName(), note.timing(), note.status(), note.evidence())).toList();
    }

    static DrawnNote harmonicTarget(List<DrawnNote> notes, String id, Span span) {
        if (id == null) return null;
        return notes.stream().filter(note -> id.equals(note.id()))
                .filter(note -> span.overlaps(note.timing()))
                .min(Comparator.comparing(DrawnNote::layer))
                .orElseThrow(() -> new IllegalArgumentException("No note " + id + " in the selected span and filters"));
    }

    private static Map<String, com.opensmpsdeck.project.Sequence> sequences(StudioProject project) {
        var sequences = new HashMap<String, com.opensmpsdeck.project.Sequence>();
        project.sequences().forEach(sequence -> sequences.put(sequence.id(), sequence));
        return sequences;
    }

    static List<PatchChange> patchChanges(StudioProject project, Span span, List<String> lanes) {
        if (project == null || project.nativeProgram() != null || project.tempo() != 0) return List.of();
        var names = new HashMap<String, String>();
        project.voices().forEach(asset -> names.put(asset.id(), asset.name()));
        project.envelopes().forEach(asset -> names.put(asset.id(), asset.name()));
        project.samples().forEach(asset -> names.put(asset.id(), asset.name()));
        var sequences = sequences(project);
        var changes = new ArrayList<PatchChange>();
        var laneAssets = new HashMap<String, String>();
        double startTick = span.seconds(span.start(), TimeBase.project) * 60;
        double endTick = span.seconds(span.end(), TimeBase.project) * 60;
        for (var clip : GroupArrangement.flatten(project).stream()
                .sorted(Comparator.comparingInt(com.opensmpsdeck.project.Clip::startTick)).toList()) {
            if (!lanes.contains(clip.lane()) || clip.endTick() <= startTick || clip.startTick() >= endTick) continue;
            var sequence = sequences.get(clip.sequenceId());
            var events = new java.util.TreeMap<Integer, String>();
            if (!clip.lane().equals("DAC")) {
                String initial = clip.lane().startsWith("FM") ? sequence.voiceId() : sequence.psgEnvelopeId();
                events.put(0, initial == null ? "" : initial);
                for (var command : sequence.commands()) {
                    switch (command.command()) {
                        case FM_VOICE, PSG_ENVELOPE -> events.put(command.tick(),
                                command.assetId() == null ? "" : command.assetId());
                        case PSG_NOISE -> {
                            if (command.arguments().getFirst() != 0) events.put(command.tick(), "Noise");
                        }
                        default -> { }
                    }
                }
            }
            sequence.triggers().forEach(hit -> events.put(hit.tick(), hit.sampleId()));
            for (int offset = 0; offset < clip.lengthTicks(); offset += sequence.lengthTicks()) {
                var visible = new LinkedHashMap<Double, String>();
                for (var event : events.entrySet()) {
                    int tick = clip.startTick() + offset + event.getKey();
                    if (tick >= clip.endTick() || tick >= endTick) break;
                    if (tick < startTick && clip.startTick() + offset + sequence.lengthTicks() <= startTick) continue;
                    visible.put(Math.max(tick, startTick), event.getValue());
                }
                visible.forEach((tick, asset) -> {
                    if (Objects.equals(laneAssets.put(clip.lane(), asset), asset)) return;
                    double time = span.base() == TimeBase.project ? tick / 60 : span.timeline().source(tick / 60);
                    changes.add(new PatchChange(clip.lane(), time, names.getOrDefault(asset,
                            asset.isEmpty() ? "Tone" : asset)));
                });
            }
        }
        return List.copyOf(changes);
    }
}
