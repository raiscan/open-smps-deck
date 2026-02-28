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

    @Test
    void multiEditUndoIsAtomic() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        byte[] ch0Original = { (byte) 0xBD, 0x18 };
        byte[] ch1Original = { (byte) 0xBF, 0x20 };
        pattern.setTrackData(0, ch0Original.clone());
        pattern.setTrackData(1, ch1Original.clone());

        // Record multi-channel edit, then mutate both channels
        mgr.recordMultiEdit(pattern, 0, 1);
        pattern.setTrackData(0, new byte[]{ (byte) 0xC1, 0x30 });
        pattern.setTrackData(1, new byte[]{ (byte) 0xC3, 0x40 });

        // Single undo should restore BOTH channels
        assertEquals(1, mgr.undoSize(), "Multi-edit should be one undo step");
        assertTrue(mgr.undo());
        assertArrayEquals(ch0Original, pattern.getTrackData(0), "Channel 0 should be restored");
        assertArrayEquals(ch1Original, pattern.getTrackData(1), "Channel 1 should be restored");
        assertEquals(0, mgr.undoSize(), "Undo stack should be empty after one undo");
    }

    @Test
    void undoCapAt500() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{});

        // Push 510 edits
        for (int i = 0; i < 510; i++) {
            mgr.recordEdit(pattern, 0);
            pattern.setTrackData(0, new byte[]{ (byte) i });
        }

        // Stack should be capped at 500
        assertEquals(500, mgr.undoSize());
    }

    @Test
    void multiEditRedoIsAtomic() {
        UndoManager mgr = new UndoManager();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, new byte[]{ (byte) 0xBD, 0x18 });
        pattern.setTrackData(1, new byte[]{ (byte) 0xBF, 0x20 });

        mgr.recordMultiEdit(pattern, 0, 1);
        byte[] ch0New = { (byte) 0xC1, 0x30 };
        byte[] ch1New = { (byte) 0xC3, 0x40 };
        pattern.setTrackData(0, ch0New.clone());
        pattern.setTrackData(1, ch1New.clone());

        mgr.undo();
        // Redo should restore both channels to the new state
        assertTrue(mgr.redo());
        assertArrayEquals(ch0New, pattern.getTrackData(0), "Channel 0 should be re-applied");
        assertArrayEquals(ch1New, pattern.getTrackData(1), "Channel 1 should be re-applied");
    }
}
