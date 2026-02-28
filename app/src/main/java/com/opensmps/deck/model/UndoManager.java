package com.opensmps.deck.model;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoManager {

    /** A single undoable edit. */
    public record Edit(Pattern pattern, int channel, byte[] previousData) {}

    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 500;

    /**
     * Record an edit BEFORE applying the change.
     * Call this with the current track data, then apply the mutation.
     */
    public void recordEdit(Pattern pattern, int channel) {
        byte[] snapshot = pattern.getTrackData(channel).clone();
        undoStack.push(new Edit(pattern, channel, snapshot));
        redoStack.clear(); // new edit invalidates redo history
        // Cap the stack
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
    }

    /**
     * Record edits for multiple channels at once (for multi-channel operations like transpose or paste).
     */
    public void recordMultiEdit(Pattern pattern, int... channels) {
        for (int ch : channels) {
            byte[] snapshot = pattern.getTrackData(ch).clone();
            undoStack.push(new Edit(pattern, ch, snapshot));
        }
        redoStack.clear();
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
    }

    /**
     * Undo the last edit. Returns true if an undo was performed.
     */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        Edit edit = undoStack.pop();
        // Save current state for redo
        byte[] currentData = edit.pattern().getTrackData(edit.channel()).clone();
        redoStack.push(new Edit(edit.pattern(), edit.channel(), currentData));
        // Restore previous data
        edit.pattern().setTrackData(edit.channel(), edit.previousData());
        return true;
    }

    /**
     * Redo the last undone edit. Returns true if a redo was performed.
     */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        Edit edit = redoStack.pop();
        // Save current state for undo
        byte[] currentData = edit.pattern().getTrackData(edit.channel()).clone();
        undoStack.push(new Edit(edit.pattern(), edit.channel(), currentData));
        // Apply redo data
        edit.pattern().setTrackData(edit.channel(), edit.previousData());
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
}
