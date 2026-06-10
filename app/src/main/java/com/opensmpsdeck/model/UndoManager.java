package com.opensmpsdeck.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Manages undo/redo for track data edits.
 *
 * <p>Each undo step is a group of one or more snapshots. Snapshots target
 * either a pattern channel or a phrase, so hierarchical (phrase) edits are
 * just as undoable as legacy pattern edits. Multi-channel operations
 * (paste, transpose) are recorded as a single atomic group so that one
 * Ctrl+Z restores all affected channels.
 */
public class UndoManager {

    /** A mutable byte[] holder a snapshot can read from and restore to. */
    private interface Target {
        byte[] get();
        void set(byte[] data);
    }

    private record PatternTarget(Pattern pattern, int channel) implements Target {
        @Override
        public byte[] get() {
            return pattern.getTrackDataDirect(channel).clone();
        }

        @Override
        public void set(byte[] data) {
            pattern.setTrackData(channel, data);
        }
    }

    private record PhraseTarget(Phrase phrase) implements Target {
        @Override
        public byte[] get() {
            return phrase.getDataDirect().clone();
        }

        @Override
        public void set(byte[] data) {
            phrase.setData(data);
        }
    }

    private record Snapshot(Target target, byte[] data) {}

    private final Deque<List<Snapshot>> undoStack = new ArrayDeque<>();
    private final Deque<List<Snapshot>> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 500;

    /**
     * Record a single-channel pattern edit BEFORE applying the change.
     * Call this with the current track data, then apply the mutation.
     */
    public void recordEdit(Pattern pattern, int channel) {
        push(List.of(snapshot(new PatternTarget(pattern, channel))));
    }

    /**
     * Record pattern edits for multiple channels atomically.
     * One undo/redo operation will restore all channels in the group.
     */
    public void recordMultiEdit(Pattern pattern, int... channels) {
        List<Snapshot> group = new ArrayList<>(channels.length);
        for (int ch : channels) {
            group.add(snapshot(new PatternTarget(pattern, ch)));
        }
        push(group);
    }

    /**
     * Record a phrase edit BEFORE applying the change.
     */
    public void recordPhraseEdit(Phrase phrase) {
        push(List.of(snapshot(new PhraseTarget(phrase))));
    }

    private static Snapshot snapshot(Target target) {
        return new Snapshot(target, target.get());
    }

    private void push(List<Snapshot> group) {
        undoStack.push(group);
        redoStack.clear();
        trimStack();
    }

    /**
     * Undo the last edit group. Returns true if an undo was performed.
     * Multi-channel edits are restored atomically.
     */
    public boolean undo() {
        return swap(undoStack, redoStack);
    }

    /**
     * Redo the last undone edit group. Returns true if a redo was performed.
     * Multi-channel edits are re-applied atomically.
     */
    public boolean redo() {
        return swap(redoStack, undoStack);
    }

    private static boolean swap(Deque<List<Snapshot>> from, Deque<List<Snapshot>> to) {
        if (from.isEmpty()) return false;
        List<Snapshot> group = from.pop();

        // Save the current state of all targets for the inverse operation
        List<Snapshot> inverse = new ArrayList<>(group.size());
        for (Snapshot s : group) {
            inverse.add(snapshot(s.target()));
        }
        to.push(inverse);

        for (Snapshot s : group) {
            s.target().set(s.data());
        }
        return true;
    }

    /** Clear all history. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public int undoSize() { return undoStack.size(); }
    public int redoSize() { return redoStack.size(); }

    private void trimStack() {
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
    }
}
