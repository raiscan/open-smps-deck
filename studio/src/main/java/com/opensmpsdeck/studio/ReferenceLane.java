package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.AudioReference;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleConsumer;

/** Footer that shares the arrangement's horizontal viewport and seconds-to-pixel transform. */
final class ReferenceLane extends Region implements AutoCloseable {
    private static final int HEIGHT = 74;
    private static final Color WAVE = Color.web("#7daefb");
    private static final Color BACKGROUND = Color.web("#101722"), LOOP = Color.web("#ffcb77", 0.10),
            LOADING = Color.web("#94a6bc"), FAILED = Color.web("#ffcb77"), AUDIO = Color.web("#203451"),
            LABEL = Color.web("#d3e5ff"), PLAYHEAD = Color.web("#ffcb77"), GUTTER = Color.web("#172130"),
            DETAIL = Color.web("#94a6bc"), EDGE = Color.web("#344257");
    private static final Font FONT = Font.font("System", 12);
    private final Canvas canvas = new Canvas(100, HEIGHT);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("studio-reference").daemon().unstarted(task));
    // Only the worker touches this cache. Entries are tied to the current WAV.
    private final Map<Double, ReferenceWave.Overview> cache = new LinkedHashMap<>();
    private Path cachedPath;
    private ReferenceWave cachedWave;
    private Future<?> pending;
    private long generation;
    private Path path;
    private AudioReference reference;
    private ReferenceWave wave;
    private ReferenceWave.Overview overview;
    private String error;
    private double overviewScale;
    private double requestedScale;
    private double pixelsPerSecond = 1;
    private double scroll;
    private double playhead = -1;
    private ReviewTransport.Range loop;
    private double pressX, pressOffset;
    private Double dragging;
    private boolean pressed;
    Runnable loaded = () -> {};
    DoubleConsumer offsetChanged = ignored -> {};

    ReferenceLane() {
        setId("reference-lane");
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        setMinWidth(0);
        setVisible(false);
        managedProperty().bind(visibleProperty());
        getChildren().add(canvas);
        canvas.setFocusTraversable(true);
        canvas.setOnMousePressed(event -> {
            if (reference == null || event.getButton() != MouseButton.PRIMARY) return;
            canvas.requestFocus();
            pressX = event.getX();
            pressOffset = reference.offsetSeconds();
            pressed = true;
        });
        canvas.setOnMouseDragged(event -> {
            if (!pressed) return;
            dragging = pressOffset + (event.getX() - pressX) / pixelsPerSecond;
            draw();
        });
        canvas.setOnMouseReleased(event -> {
            pressed = false;
            Double changed = dragging;
            dragging = null;
            if (changed != null) offsetChanged.accept(changed);
            draw();
        });
        canvas.setOnKeyPressed(event -> {
            if (reference == null) return;
            var key = event.getCode();
            if (key == KeyCode.LEFT || key == KeyCode.RIGHT) {
                offsetChanged.accept(reference.offsetSeconds() + (key == KeyCode.LEFT ? -.01 : .01));
                event.consume();
            }
        });
    }

    ReferenceWave wave() { return wave; }

    boolean loading() { return reference != null && wave == null && error == null; }

    void source(Path file, AudioReference source) {
        Path next = source == null ? null : source.resolve(file);
        if (!Objects.equals(reference, source)) {
            pressed = false;
            dragging = null;
        }
        reference = source;
        setVisible(source != null);
        if (!Objects.equals(path, next)) {
            path = next;
            wave = null;
            overview = null;
            error = null;
            requestedScale = 0;
            generation++;
            if (pending != null) pending.cancel(true);
        }
        draw();
    }

    void viewport(double width, double scroll, double pixelsPerSecond, double playhead, ReviewTransport.Range loop) {
        this.scroll = scroll;
        this.pixelsPerSecond = pixelsPerSecond;
        this.playhead = playhead;
        this.loop = loop;
        if (canvas.getWidth() != width) canvas.setWidth(width);
        draw();
    }

    @Override protected void layoutChildren() {
        canvas.relocate(0, 0);
    }

    private void requestOverview() {
        if (path == null) return;
        var window = getScene() == null ? null : getScene().getWindow();
        double scale = pixelsPerSecond * (window == null ? 1 : window.getOutputScaleX());
        if (scale == requestedScale) return;
        requestedScale = scale;
        if (pending != null) pending.cancel(true);
        long request = ++generation;
        Path source = path;
        pending = worker.submit(() -> {
            try {
                if (!source.equals(cachedPath)) {
                    cache.clear();
                    cachedWave = ReferenceWave.read(source);
                    cachedPath = source;
                }
                double effectiveScale = Math.min(scale, 2_000_000 / cachedWave.duration());
                var result = cache.get(effectiveScale);
                if (result == null) {
                    result = cachedWave.overview(effectiveScale);
                    if (cache.size() == 3) cache.remove(cache.keySet().iterator().next());
                    cache.put(effectiveScale, result);
                }
                var readyWave = cachedWave;
                var readyOverview = result;
                Platform.runLater(() -> {
                    if (request != generation) return;
                    boolean newWave = wave != readyWave;
                    wave = readyWave;
                    overview = readyOverview;
                    overviewScale = effectiveScale;
                    error = null;
                    if (newWave) loaded.run();
                    draw();
                });
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    if (request != generation) return;
                    error = failure.getMessage();
                    draw();
                });
            }
        });
    }

    private void draw() {
        if (reference == null) return;
        requestOverview();
        var graphics = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        graphics.setFill(BACKGROUND);
        graphics.fillRect(0, 0, width, HEIGHT);
        double start = dragging == null ? reference.offsetSeconds() : dragging;
        double origin = x(start);
        if (loop != null) {
            graphics.setFill(LOOP);
            graphics.fillRect(x(loop.start()), 0, (loop.end() - loop.start()) * pixelsPerSecond, HEIGHT);
        }
        if (overview == null) {
            graphics.setFill(error == null ? LOADING : FAILED);
            graphics.fillText(error == null ? "Loading waveform…" : error, TimelineView.GUTTER + 10, 44);
        } else {
            double end = x(start + wave.duration());
            graphics.setFill(AUDIO);
            graphics.fillRect(Math.max(TimelineView.GUTTER, origin), 0,
                    Math.max(0, Math.min(width, end) - Math.max(TimelineView.GUTTER, origin)), HEIGHT);
            graphics.setStroke(WAVE);
            double step = pixelsPerSecond / overviewScale;
            graphics.setLineWidth(Math.min(1, step));
            int first = Math.max(0, (int) ((TimelineView.GUTTER - origin) / step));
            int last = Math.min(overview.minimum().length, (int) Math.ceil((width - origin) / step));
            for (int i = first; i < last; i++) {
                double column = origin + (i + .5) * step;
                graphics.strokeLine(column, 45 - overview.maximum()[i] * 22,
                        column, 45 - overview.minimum()[i] * 22);
            }
            graphics.setLineWidth(1);
            graphics.setFill(LABEL);
            graphics.fillText(reference.label(), Math.max(TimelineView.GUTTER, origin) + 8, 17);
        }
        graphics.setStroke(PLAYHEAD);
        if (playhead >= 0) graphics.strokeLine(x(playhead), 0, x(playhead), HEIGHT);
        graphics.setFill(GUTTER);
        graphics.fillRect(0, 0, TimelineView.GUTTER, HEIGHT);
        graphics.setFont(FONT);
        graphics.setFill(WAVE);
        graphics.fillText("REFERENCE", 14, 28);
        graphics.setFill(DETAIL);
        graphics.fillText(String.format(java.util.Locale.ROOT, "%+.3f s", start), 14, 48);
        graphics.setStroke(EDGE);
        graphics.strokeLine(0, .5, width, .5);
    }

    private double x(double seconds) { return TimelineView.GUTTER + seconds * pixelsPerSecond - scroll; }

    @Override public void close() {
        generation++;
        worker.shutdownNow();
    }
}
