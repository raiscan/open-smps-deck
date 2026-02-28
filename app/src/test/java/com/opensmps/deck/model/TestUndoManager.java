package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestUndoManager {

    @Test
    void undoRestoresPreviousData() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        byte[] original = { (byte) 0xBD, 0x18 };
        pattern.setTrackData(0, original.clone());

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBF, 0x18 });

        assertTrue(mgr.undo());
        assertArrayEquals(original, pattern.getTrackData(0));
    }

    @Test
    void redoReappliesChange() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBD, 0x18 });

        mgr.recordEdit(pattern, 0);
        byte[] changed = { (byte) 0xBF, 0x18 };
        pattern.setTrackData(0, changed.clone());

        mgr.undo();
        assertTrue(mgr.redo());
        assertArrayEquals(changed, pattern.getTrackData(0));
    }

    @Test
    void undoEmptyReturnsFalse() {
        UndoManager mgr = new UndoManager();
        assertFalse(mgr.undo());
    }

    @Test
    void redoEmptyReturnsFalse() {
        UndoManager mgr = new UndoManager();
        assertFalse(mgr.redo());
    }

    @Test
    void newEditClearsRedoStack() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBD, 0x18 });

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBF, 0x18 });

        mgr.undo(); // can redo now
        assertTrue(mgr.canRedo());

        mgr.recordEdit(pattern, 0); // new edit clears redo
        pattern.setTrackData(0, new byte[]{ (byte) 0xC1, 0x18 });

        assertFalse(mgr.canRedo());
    }

    @Test
    void multipleUndos() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        byte[] v1 = { (byte) 0xBD, 0x18 };
        byte[] v2 = { (byte) 0xBF, 0x18 };
        byte[] v3 = { (byte) 0xC1, 0x18 };

        pattern.setTrackData(0, v1.clone());

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, v2.clone());

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, v3.clone());

        mgr.undo(); // back to v2
        assertArrayEquals(v2, pattern.getTrackData(0));

        mgr.undo(); // back to v1
        assertArrayEquals(v1, pattern.getTrackData(0));
    }

    @Test
    void clearResetsAll() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBD, 0x18 });

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBF, 0x18 });

        mgr.clear();
        assertFalse(mgr.canUndo());
        assertFalse(mgr.canRedo());
    }

    @Test
    void undoSizeAndRedoSize() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{});

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, new byte[]{ 0x01 });

        mgr.recordEdit(pattern, 0);
        pattern.setTrackData(0, new byte[]{ 0x02 });

        assertEquals(2, mgr.undoSize());
        assertEquals(0, mgr.redoSize());

        mgr.undo();
        assertEquals(1, mgr.undoSize());
        assertEquals(1, mgr.redoSize());
    }
}
