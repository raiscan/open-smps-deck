package com.opensmpsdeck.studio;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

/**
 * A wide logical surface with only a viewport-sized pixel buffer.
 *
 * <p>{@link #draw()} only marks the view dirty. The view paints once, after the
 * next layout pass, however many state changes, scroll updates and resizes
 * happen in between. Code that reads what was painted, such as hit testing,
 * calls {@link #flush()} first.
 */
abstract class ViewportCanvas extends Region {
    final Canvas canvas = new Canvas(800, 400);
    final ScrollPane scroll = new ScrollPane(this);
    double offset;
    double contentWidth = 1200;
    double contentHeight = 400;
    private final double minimumContentWidth;
    private final Runnable afterLayout = this::flush;
    private Scene pulseScene;
    private boolean dirty;
    private boolean painting;

    ViewportCanvas() {
        this(400);
    }

    ViewportCanvas(double minimumContentWidth) {
        this.minimumContentWidth = minimumContentWidth;
        getChildren().add(canvas);
        scroll.setPannable(false);
        scroll.getStyleClass().add("editor-scroll");
        scroll.viewportBoundsProperty().addListener((o,a,b) -> updateViewport());
        scroll.hvalueProperty().addListener((o,a,b) -> updateViewport());
        widthProperty().addListener((o,a,b) -> updateViewport());
        sceneProperty().addListener((o, previous, next) -> attach(next));
        canvas.setFocusTraversable(true);
    }

    private void attach(Scene next) {
        if (pulseScene != null) pulseScene.removePostLayoutPulseListener(afterLayout);
        pulseScene = next;
        if (next != null) {
            next.addPostLayoutPulseListener(afterLayout);
            if (dirty) Platform.requestNextPulse();
        }
    }

    void dimensions(double width, double height) {
        contentWidth = Math.max(minimumContentWidth, width); contentHeight = height;
        setMinSize(contentWidth, height); setPrefSize(contentWidth, height);
        // Publish the new content bounds before restoring a scroll anchor.
        // Otherwise ScrollPane normalizes hvalue again on the next layout pass.
        resize(contentWidth, viewportHeight(height));
        updateViewport(); requestLayout();
    }
    private double viewportHeight(double minimumHeight) {
        return scroll.isFitToHeight() ? Math.max(minimumHeight, scroll.getViewportBounds().getHeight()) : minimumHeight;
    }

    final void updateViewport() {
        double width = Math.max(100, scroll.getViewportBounds().getWidth());
        if (scroll.isFitToHeight()) contentHeight = viewportHeight(getMinHeight());
        offset = scroll.getHvalue() * Math.max(0, contentWidth - width);
        canvas.setTranslateX(offset);
        // Resizing a canvas reallocates its texture, so skip no-op updates.
        if (canvas.getWidth() != width) canvas.setWidth(width);
        if (canvas.getHeight() != contentHeight) canvas.setHeight(contentHeight);
        draw();
    }
    @Override protected void layoutChildren() { updateViewport(); }
    void overlay(javafx.scene.Node node) { getChildren().add(node); }

    /** Schedules one repaint for the next pulse. Off-scene views paint at once. */
    final void draw() {
        if (painting) return;
        settle();
        dirty = true;
        if (pulseScene == null) flush();
        else Platform.requestNextPulse();
    }

    /** Paints now if a repaint is pending, so hit tests and snapshots see the current state. */
    final void flush() {
        if (!dirty || painting) return;
        dirty = false;
        painting = true;
        try {
            paint();
        } finally {
            painting = false;
        }
    }

    boolean paintPending() { return dirty; }

    /** Updates view state that must not wait for the paint. It runs on every draw request. */
    void settle() {}

    abstract void paint();
}
