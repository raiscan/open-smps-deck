package com.opensmpsdeck.studio;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Paint scheduling for the viewport canvases. Run under an isolated display with -Dstudio.ui.test=true. */
final class ViewportCanvasTest {
    private static final class Counting extends ViewportCanvas {
        final AtomicInteger paints = new AtomicInteger();
        @Override void paint() { paints.incrementAndGet(); }
    }

    @BeforeAll static void toolkit() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("studio.ui.test"), "UI test requires an explicitly isolated display");
        var ready = new CountDownLatch(1);
        try {
            Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void offSceneViewsPaintAtOnce() throws Exception {
        fx(() -> {
            var view = new Counting();
            int before = view.paints.get();
            view.draw();
            assertEquals(before + 1, view.paints.get());
            assertFalse(view.paintPending());
            return null;
        });
    }

    @Test void manyRequestsInOnePulsePaintOnce() throws Exception {
        var view = fx(Counting::new);
        var stage = fx(() -> {
            var window = new Stage();
            view.dimensions(5000, 300);
            window.setScene(new Scene(view.scroll, 600, 320));
            window.show();
            return window;
        });
        try {
            awaitPainted(view);
            int before = fx(() -> {
                int count = view.paints.get();
                for (int i = 0; i < 20; i++) {
                    view.draw();
                    view.scroll.setHvalue(i / 20.0);
                }
                assertTrue(view.paintPending());
                return count;
            });
            awaitPainted(view);
            assertEquals(before + 1, fx(view.paints::get));
            // Hit tests flush a pending paint synchronously.
            fx(() -> {
                view.draw();
                view.flush();
                assertFalse(view.paintPending());
                assertEquals(before + 2, view.paints.get());
                return null;
            });
        } finally {
            fx(() -> { stage.close(); return null; });
        }
    }

    private static void awaitPainted(ViewportCanvas view) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(view::paintPending)) {
            if (System.nanoTime() > deadline) fail("The view did not paint");
            Thread.sleep(10);
        }
        // Let any layout from the first paint settle.
        Thread.sleep(100);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
