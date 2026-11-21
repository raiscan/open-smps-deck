package com.opensmpsdeck.studio;

import java.util.ArrayDeque;
import java.util.Deque;

/** Snapshot history for Studio documents and reconstruction intents. */
final class EditHistory<T> {
    private final Deque<T> undo = new ArrayDeque<>();
    private final Deque<T> redo = new ArrayDeque<>();

    void record(T previous) {
        undo.push(previous);
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
    }

    boolean canStep(boolean forward) { return !source(forward).isEmpty(); }

    T step(T current, boolean forward) {
        var from = source(forward);
        if (from.isEmpty()) return current;
        source(!forward).push(current);
        return from.pop();
    }

    private Deque<T> source(boolean forward) { return forward ? redo : undo; }

    void clear() {
        undo.clear();
        redo.clear();
    }
}
