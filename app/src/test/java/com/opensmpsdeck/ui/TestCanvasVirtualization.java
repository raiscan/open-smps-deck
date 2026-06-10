package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.Chain;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.HierarchicalArrangement;
import com.opensmpsdeck.model.Pattern;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.Song;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Long songs must not produce a Canvas larger than the GPU texture limit
 * (~4096px on common D3D configurations). The canvas must stay viewport-sized
 * while a spacer pane carries the full logical size for scrollbar proportions.
 * Regression test for the Prism NPE spam when importing long SMPS rips
 * (e.g. "0D Launch Base 1.s3k").
 */
class TestCanvasVirtualization {

    /** Conservative cap: anything above this risks RTTexture allocation failure. */
    private static final double MAX_SAFE_CANVAS_DIMENSION = 4096;

    @BeforeAll
    static void bootFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            latch.countDown();
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS), "JavaFX failed to start");
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "FX task timed out");
        if (error.get() != null) {
            if (error.get() instanceof AssertionError ae) throw ae;
            throw new RuntimeException(error.get());
        }
    }

    /** Build track bytecode with the given number of note rows. */
    private static byte[] longTrack(int rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < rows; i++) {
            out.write(0x81 + (i % 12)); // note
            out.write(0x10);            // duration
        }
        out.write(0xF2); // STOP
        return out.toByteArray();
    }

    private static Song longSong(int rows) {
        Song song = new Song();
        song.getPatterns().clear();
        Pattern pattern = new Pattern(0, 64);
        pattern.setTrackData(0, longTrack(rows));
        song.getPatterns().add(pattern);
        return song;
    }

    private static Canvas findCanvas(javafx.scene.Node content) {
        if (content instanceof Canvas c) return c;
        if (content instanceof Pane p) {
            for (javafx.scene.Node child : p.getChildren()) {
                Canvas found = findCanvas(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void patternModeCanvasStaysWithinTextureLimit() throws Exception {
        onFx(() -> {
            TrackerGrid grid = new TrackerGrid();
            grid.setSong(longSong(2000)); // logical height = 24 + 20*2000 = 40024px

            Canvas canvas = findCanvas(grid.getContent());
            assertNotNull(canvas, "TrackerGrid should contain a canvas");
            assertTrue(canvas.getHeight() <= MAX_SAFE_CANVAS_DIMENSION,
                    "Pattern-mode canvas height " + canvas.getHeight()
                            + " exceeds safe texture size");

            // Scrollbars must still reflect the full logical height via the content node
            assertTrue(grid.getContent().prefHeight(-1) >= 40000,
                    "Scroll content should carry full logical height, got "
                            + grid.getContent().prefHeight(-1));
        });
    }

    @Test
    void phraseModeCanvasStaysWithinTextureLimit() throws Exception {
        onFx(() -> {
            TrackerGrid grid = new TrackerGrid();
            grid.setSong(longSong(4));
            Phrase phrase = new Phrase(1, "Long", ChannelType.FM);
            phrase.setData(longTrack(2000));
            grid.setPhrase(phrase, 0);

            Canvas canvas = findCanvas(grid.getContent());
            assertNotNull(canvas, "TrackerGrid should contain a canvas");
            assertTrue(canvas.getHeight() <= MAX_SAFE_CANVAS_DIMENSION,
                    "Phrase-mode canvas height " + canvas.getHeight()
                            + " exceeds safe texture size");
            assertTrue(grid.getContent().prefHeight(-1) >= 40000,
                    "Scroll content should carry full logical height, got "
                            + grid.getContent().prefHeight(-1));
        });
    }

    @Test
    void songViewCanvasStaysWithinTextureLimit() throws Exception {
        onFx(() -> {
            HierarchicalArrangement arr = new HierarchicalArrangement();
            Phrase phrase = arr.getPhraseLibrary().createPhrase("Long", ChannelType.FM);
            phrase.setData(longTrack(200));
            Chain chain = arr.getChain(0);
            for (int i = 0; i < 10; i++) {
                ChainEntry entry = new ChainEntry(phrase.getId());
                entry.setRepeatCount(16);
                chain.getEntries().add(entry); // 200 rows * 16 * 6px = 19200px each
            }

            SongView view = new SongView();
            view.setArrangement(arr);

            Canvas canvas = findCanvas(view.getContent());
            assertNotNull(canvas, "SongView should contain a canvas");
            assertTrue(canvas.getWidth() <= MAX_SAFE_CANVAS_DIMENSION,
                    "SongView canvas width " + canvas.getWidth()
                            + " exceeds safe texture size");
            assertTrue(view.getContent().prefWidth(-1) >= 100000,
                    "Scroll content should carry full logical width, got "
                            + view.getContent().prefWidth(-1));
        });
    }
}
