package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.Sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

final class ReconstructionClipLinks {
    record Span(String group, int start, int end) {}
    private record Key(String group, String lane, Sequence content) {}

    private final List<Span> spans;
    private final Map<Key, String> linkedSources = new HashMap<>();

    private ReconstructionClipLinks(List<Span> spans) { this.spans = spans; }

    static ReconstructionClipLinks from(ReconstructionStructure.Result structure) {
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
        return new ReconstructionClipLinks(spans);
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
