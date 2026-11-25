package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import javafx.scene.canvas.Canvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ReconstructionBranchOverlay {
    enum Kind { ADDED, REMOVED, CHANGED }

    record Delta(Kind kind, Overlay before, Overlay after) {
        Overlay note() { return after == null ? before : after; }
    }

    record Layer(ReconstructionVoices.Style style, List<Delta> changes) {}

    static ReconstructionVoices.Style style(int index) {
        var patterns = ReconstructionVoices.Pattern.values();
        return new ReconstructionVoices.Style(ReconstructionVoices.variantColor(4 + index),
                patterns[(1 + index) % patterns.length]);
    }

    static Canvas swatch(ReconstructionVoices.Style style) {
        var canvas = new Canvas(19, 12);
        style.draw(canvas.getGraphicsContext2D(), 1, 1, 17, 10, .85);
        return canvas;
    }

    static List<Delta> diff(ReconstructionInspection parent, ReconstructionInspection branch, Set<String> changed) {
        var left = sounding(parent);
        var result = new ArrayList<Delta>();
        sounding(branch).forEach((id, note) -> {
            var previous = left.remove(id);
            if (previous == null) result.add(new Delta(Kind.ADDED, null, note));
            else if (changed.contains(note.id()))
                result.add(new Delta(Kind.CHANGED, previous, note));
        });
        left.values().forEach(note -> result.add(new Delta(Kind.REMOVED, note, null)));
        return List.copyOf(result);
    }

    private static LinkedHashMap<String, Overlay> sounding(ReconstructionInspection model) {
        return model.overlays().stream().filter(Overlay::sounds).collect(Collectors.toMap(
                Overlay::eventId, Function.identity(), (first, next) -> first, LinkedHashMap::new));
    }

    static List<String> summary(List<Delta> changes) {
        var perPart = changes.stream().collect(Collectors.groupingBy(
                delta -> delta.note().part(), LinkedHashMap::new, Collectors.toList()));
        return perPart.entrySet().stream().map(entry -> {
            var part = entry.getValue();
            var name = part.getFirst().note().partName();
            if (name.isBlank()) name = entry.getKey() < 0 ? "Unassigned" : "Part " + entry.getKey();
            var times = part.stream().flatMap(change -> java.util.stream.Stream.of(change.before(), change.after()))
                    .filter(Objects::nonNull).map(Overlay::interval).toList();
            double start = times.stream().mapToDouble(ReconstructionInspection.Interval::sourceStart).min().orElse(0);
            double end = times.stream().mapToDouble(ReconstructionInspection.Interval::sourceEnd).max().orElse(0);
            long added = part.stream().filter(delta -> delta.kind() == Kind.ADDED).count();
            long removed = part.stream().filter(delta -> delta.kind() == Kind.REMOVED).count();
            return String.format(Locale.ROOT, "%s · %.2f–%.2f s   +%d  −%d  ~%d",
                    name, start, end, added, removed, part.size() - added - removed);
        }).toList();
    }
}
