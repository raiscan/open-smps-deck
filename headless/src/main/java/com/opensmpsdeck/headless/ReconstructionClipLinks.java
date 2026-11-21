package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

final class ReconstructionClipLinks {
    static final String LAYOUT = "assembly-layout.json";
    record Layout(int version, Map<String, List<Integer>> anchors) {
        static Layout of(List<Clip> clips) {
            var lanes = new java.util.TreeMap<String, List<Integer>>();
            for (var clip : clips) lanes.computeIfAbsent(clip.lane(), ignored -> new ArrayList<>()).add(clip.startTick());
            lanes.replaceAll((lane, starts) -> starts.stream().distinct().sorted().toList());
            return new Layout(ReconstructionStages.VERSION, lanes);
        }

        static Layout read(Path work) throws IOException {
            Path layout = work.resolve(LAYOUT);
            if (Files.exists(layout)) return ReconstructionArtifacts.read(layout, Layout.class);
            Path project = work.resolve("arrangement.smps.json");
            return Files.exists(project) ? of(ProjectFiles.load(project).project().clips()) : null;
        }
    }

    record Span(String group, int start, int end) {}
    private record Key(String group, String lane, Sequence content) {}

    private final List<Span> spans;
    private final Layout layout;
    private final Map<Key, String> linkedSources = new HashMap<>();

    private ReconstructionClipLinks(List<Span> spans, Layout layout) {
        this.spans = spans;
        this.layout = layout;
    }

    static ReconstructionClipLinks from(ReconstructionStructure.Result structure) {
        return from(structure, null);
    }

    static ReconstructionClipLinks from(ReconstructionStructure.Result structure, Layout layout) {
        var groups = new HashMap<String, String>();
        if (structure != null)
            for (var repeat : structure.repeats()) for (String bar : repeat.bars()) groups.put(bar, repeat.id());
        var spans = new ArrayList<Span>();
        if (structure != null) for (var bar : structure.bars()) {
            String group = groups.get(bar.id());
            int start = S3kReconstruction.toTicks(Math.round(bar.start() * 1_000_000));
            int end = S3kReconstruction.toTicks(Math.round(bar.end() * 1_000_000));
            if (group != null && end > start) spans.add(new Span(group, start, end));
        }
        return new ReconstructionClipLinks(spans, layout);
    }

    /** Omit a boundary on this lane if a note would have to be cut or retriggered there. */
    Map<Integer, Span> indexes(int count, IntUnaryOperator start, IntUnaryOperator end) {
        var result = new HashMap<Integer, Span>();
        int index = 0;
        for (var span : spans) {
            while (index < count && start.applyAsInt(index) < span.start) index++;
            int first = index;
            if (first == count || start.applyAsInt(first) >= span.end) continue;
            if (first > 0 && end.applyAsInt(first - 1) > span.start) continue;
            while (index + 1 < count && start.applyAsInt(index + 1) < span.end) index++;
            if (end.applyAsInt(index) > span.end) continue;
            for (int i = first; i <= index; i++) result.put(i, span);
        }
        return result;
    }

    record Chunk(int first, int limit, int tick, Span span) {}

    List<Chunk> chunks(String lane, int count, IntUnaryOperator start, IntUnaryOperator end,
                       IntPredicate noise, int maxTicks) {
        var linked = indexes(count, start, end);
        var saved = layout == null ? List.<Integer>of() : layout.anchors().getOrDefault(lane, List.of());
        var anchors = saved.stream().mapToInt(Integer::intValue).toArray();
        var chunks = new ArrayList<Chunk>();
        int boundary = 0;
        // Apply the original chunking only when no saved anchor governs this part of the lane.
        boolean anchored = false;
        for (int first = 0; first < count;) {
            int tick = start.applyAsInt(first);
            int anchor = -1;
            while (boundary < anchors.length && anchors[boundary] <= tick) {
                if (first == 0 || end.applyAsInt(first - 1) <= anchors[boundary]) anchor = anchors[boundary];
                boundary++;
            }
            anchored |= anchor >= 0;
            int next = boundary < anchors.length ? anchors[boundary] : Integer.MAX_VALUE;
            var span = linked.get(first);
            int limit = first + 1;
            while (limit < count && noise.test(limit) == noise.test(first)) {
                int attack = start.applyAsInt(limit);
                // A gate or release command can overrun an anchor after a timing edit.
                // Keep the whole placement and resume using the next reachable anchor.
                while (attack >= next && end.applyAsInt(limit - 1) > next) {
                    boundary++;
                    next = boundary < anchors.length ? anchors[boundary] : Integer.MAX_VALUE;
                }
                if (attack >= next) break;
                if (!anchored && (linked.get(limit) != span || span == null && attack - tick >= maxTicks)) break;
                if (linked.get(limit) != span) span = null;
                limit++;
            }
            chunks.add(new Chunk(first, limit, anchor >= 0 ? anchor : tick, span));
            first = limit;
        }
        return chunks;
    }

    void add(Sequence source, String lane, int start, Span span, List<Sequence> sequences, List<Clip> clips) {
        String id = source.id();
        String sourceId = id;
        if (span != null) {
            // Compare compiled content, including asset choices and all timed commands.
            var content = new Sequence("", "", source.type(), source.lengthTicks(), source.voiceId(), source.volume(),
                    source.notes(), source.commands(), source.repeatState(), source.psgEnvelopeId(),
                    source.modulationEnvelopeId(), source.triggers());
            sourceId = linkedSources.computeIfAbsent(new Key(span.group, lane, content), ignored -> id);
        }
        if (sourceId.equals(id)) sequences.add(source);
        clips.add(new Clip("clip-" + id, sourceId, lane, start, source.lengthTicks()));
    }
}
