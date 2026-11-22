package com.opensmpsdeck.studio;

import javafx.scene.canvas.Canvas;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

/** A wide logical surface with only a viewport-sized pixel buffer. */
abstract class ViewportCanvas extends Region {
    final Canvas canvas = new Canvas(800, 400);
    final ScrollPane scroll = new ScrollPane(this);
    double offset;
    double contentWidth = 1200;
    double contentHeight = 400;
    private final double minimumContentWidth;

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
        canvas.setFocusTraversable(true);
    }
    void dimensions(double width, double height) {
        contentWidth = Math.max(minimumContentWidth, width); contentHeight = height;
        setMinSize(contentWidth, height); setPrefSize(contentWidth, height);
        // Publish the new content bounds before restoring a scroll anchor.
        // Otherwise ScrollPane normalizes hvalue again on the next layout pass.
        resize(contentWidth, height);
        updateViewport(); requestLayout();
    }
    final void updateViewport() {
        double width = Math.max(100, scroll.getViewportBounds().getWidth());
        offset = scroll.getHvalue() * Math.max(0, contentWidth - width);
        canvas.setTranslateX(offset);
        canvas.setWidth(width); canvas.setHeight(contentHeight);
        draw();
    }
    @Override protected void layoutChildren() { updateViewport(); }
    abstract void draw();
}
