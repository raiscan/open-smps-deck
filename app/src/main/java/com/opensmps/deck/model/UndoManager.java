package com.opensmps.deck.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Manages undo/redo for track data edits.
 *
 * <p>Each undo step is a group of one or more channel snapshots.
 * Multi-channel operations (paste, transpose) are recorded as a single
 * atomic group so that one Ctrl+Z restores all affected channels.
 */
public class UndoManager {

    /** A single channel's snapshot before an edit. */
    public record Edit(Pattern pattern, int channel, byte[] data) {}

    private final Deque<List<Edit>> undoStack = new ArrayDeque<>();
    private final Deque<List<Edit>> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 500;

    /**
     * Record a single-channel edit BEFORE applying the change.
     * Call this with the current track data, then apply the mutation.
     */
    public void recordEdit(Pattern pattern, int channel) {
        byte[] snapshot = pattern.getTrackData(channel).clone();
        List<Edit> group = List.of(new Edit(pattern, channel, snapshot));
        undoStack.push(new ArrayList<>(group));
        redoStack.clear();
        trimStack();
    }

    /**
     * Record edits for multiple channels atomically.
     * One undo/redo operation will restore all channels in the group.
     */
    public void recordMultiEdit(Pattern pattern, int... channels) {
        List<Edit> group = new ArrayList<>(channels.length);
        for (int ch : channels) {
            byte[] snapshot = pattern.getTrackData(ch).clone();
            group.add(new Edit(pattern, ch, snapshot));
        }
        undoStack.push(group);
        redoStack.clear();
        trimStack();
    }

    /**
     * Undo the last edit group. Returns true if an undo was performed.
     * Multi-channel edits are restored atomically.
     */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        List<Edit> group = undoStack.pop();

        // Save current state of all channels for redo
        List<Edit> redoGroup = new ArrayList<>(group.size());
        for (Edit edit : group) {
            byte[] currentData = edit.pattern().getTrackData(edit.channel()).clone();
            redoGroup.add(new Edit(edit.pattern(), edit.channel(), currentData));
        }
        redoStack.push(redoGroup);

        // Restore all channels in the group
        for (Edit edit : group) {
            edit.pattern().setTrackData(edit.channel(), edit.data());
        }
        return true;
    }

    /**
     * Redo the last undone edit group. Returns true if a redo was performed.
     * Multi-channel edits are re-applied atomically.
     */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        List<Edit> group = redoStack.pop();

        // Save current state for undo
        List<Edit> undoGroup = new ArrayList<>(group.size());
        for (Edit edit : group) {
            byte[] currentData = edit.pattern().getTrackData(edit.channel()).clone();
            undoGroup.add(new Edit(edit.pattern(), edit.channel(), currentData));
        }
        undoStack.push(undoGroup);

        // Apply redo data
        for (Edit edit : group) {
            edit.pattern().setTrackData(edit.channel(), edit.data());
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
