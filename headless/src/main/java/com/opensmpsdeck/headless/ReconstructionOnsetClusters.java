package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

final class ReconstructionOnsetClusters {
    private static final double WINDOW = .020;

    private ReconstructionOnsetClusters() {}

    record Cluster<T>(int part, double start, List<T> notes) {}

    static <T> List<Cluster<T>> group(List<T> notes, ToIntFunction<T> part, ToDoubleFunction<T> onset) {
        var ordered = notes.stream().sorted(Comparator.comparingInt(part).thenComparingDouble(onset)).toList();
        var clusters = new ArrayList<Cluster<T>>();
        for (T note : ordered) {
            int partId = part.applyAsInt(note);
            double start = onset.applyAsDouble(note);
            if (clusters.isEmpty() || clusters.getLast().part != partId
                    || start - clusters.getLast().start > WINDOW + 1e-9)
                clusters.add(new Cluster<>(partId, start, new ArrayList<>()));
            clusters.getLast().notes.add(note);
        }
        clusters.sort(Comparator.comparingDouble(Cluster<T>::start).thenComparingInt(Cluster::part));
        return clusters;
    }
}
