package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.NoteHandoff;
import com.opensmpsdeck.headless.NoteScreenshot;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

final class ScreenshotLasso {
    private static final DataFormat PNG = pngFormat();
    private final ViewportCanvas view;
    private final Rectangle outline = new Rectangle();
    private final ToggleButton toggle = new ToggleButton("Screenshot lasso");
    private final Button save = new Button("Save lasso PNG…");
    private final HBox controls = new HBox(5, toggle, save);
    private final Consumer<String> status;
    private double x, y;
    private boolean dragging;
    private byte[] lastPng;
    private String reference;

    ScreenshotLasso(ViewportCanvas view, Function<Rectangle2D, NoteHandoff.Capture> payload, Consumer<String> status,
                    BooleanSupplier panning) {
        this(view, payload, status, panning, null);
    }

    static ScreenshotLasso region(ViewportCanvas view, Consumer<Rectangle2D> select, Consumer<String> status,
                                  BooleanSupplier panning) {
        return new ScreenshotLasso(view, null, status, panning, select);
    }

    private ScreenshotLasso(ViewportCanvas view, Function<Rectangle2D, NoteHandoff.Capture> payload,
                           Consumer<String> status, BooleanSupplier panning, Consumer<Rectangle2D> select) {
        this.view = view;
        this.status = status;
        outline.setManaged(false);
        outline.setMouseTransparent(true);
        outline.setVisible(false);
        outline.setFill(Color.web("#43d6bf", .13));
        outline.setStroke(Color.web("#8dfce9"));
        outline.getStrokeDashArray().setAll(5.0, 3.0);
        outline.translateXProperty().bind(view.canvas.translateXProperty());
        view.overlay(outline);
        if (select == null) {
            toggle.setId("screenshot-lasso");
            toggle.setTooltip(new Tooltip("Drag a screenshot region (Ctrl/Cmd+Shift+L)"));
        } else {
            toggle.setId("reconstruction-branch-region");
            toggle.setText("Pick region");
            toggle.setTooltip(new Tooltip("Drag a rectangle to choose its time span. Esc cancels."));
        }
        save.setId("save-lasso-png");
        save.setVisible(false);
        save.managedProperty().bind(save.visibleProperty());
        save.setOnAction(event -> save());
        toggle.selectedProperty().addListener((ignored, before, active) -> {
            clearGesture();
            view.canvas.setCursor(active ? Cursor.CROSSHAIR : Cursor.DEFAULT);
            if (active) view.canvas.requestFocus();
        });
        controls.visibleProperty().addListener((ignored, before, visible) -> {
            if (!visible) toggle.setSelected(false);
        });
        view.canvas.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (select != null && event.getCode() == KeyCode.ESCAPE && toggle.isSelected()) {
                toggle.setSelected(false);
                event.consume();
            } else if (select == null && controls.isVisible() && event.getCode() == KeyCode.L
                    && event.isShortcutDown() && event.isShiftDown() && !event.isAltDown()) {
                toggle();
                event.consume();
            }
        });
        view.canvas.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (toggle.isSelected() && !panning.getAsBoolean()) {
                view.canvas.setCursor(Cursor.CROSSHAIR);
                event.consume();
            }
        });
        view.canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!toggle.isSelected() || panning.getAsBoolean() || event.getButton() != MouseButton.PRIMARY) return;
            view.canvas.requestFocus();
            x = event.getX();
            y = event.getY();
            dragging = true;
            event.consume();
        });
        view.canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!dragging) return;
            if (panning.getAsBoolean()) {
                clearGesture();
                return;
            }
            var box = bounds(event.getX(), event.getY());
            outline.setX(box.getMinX());
            outline.setY(box.getMinY());
            outline.setWidth(box.getWidth());
            outline.setHeight(box.getHeight());
            outline.setVisible(true);
            event.setDragDetect(false);
            event.consume();
        });
        view.canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!dragging || event.getButton() != MouseButton.PRIMARY) return;
            if (panning.getAsBoolean()) {
                clearGesture();
                return;
            }
            var box = bounds(event.getX(), event.getY());
            clearGesture();
            if (box.getWidth() >= 2 && box.getHeight() >= 2) {
                if (select == null) capture(box, payload);
                else {
                    toggle.setSelected(false);
                    select.accept(box);
                }
            }
            event.consume();
        });
    }

    HBox controls() { return controls; }

    void toggle() {
        toggle.setSelected(!toggle.isSelected());
    }

    void cancel() { toggle.setSelected(false); }

    static void addTo(StudioContextMenu menu, Runnable action) {
        menu.add("Screenshot lasso", StudioContextMenu.shortcut("Shift+L"), null, action);
    }

    private void clearGesture() {
        dragging = false;
        outline.setVisible(false);
    }

    private Rectangle2D bounds(double endX, double endY) {
        var viewport = view.scroll.lookup(".viewport");
        Bounds visible = viewport == null ? view.canvas.getLayoutBounds()
                : view.canvas.sceneToLocal(viewport.localToScene(viewport.getLayoutBounds()));
        double left = Math.max(0, visible.getMinX()), top = Math.max(0, visible.getMinY());
        double right = Math.max(left, Math.min(view.canvas.getWidth(), visible.getMaxX()));
        double bottom = Math.max(top, Math.min(view.canvas.getHeight(), visible.getMaxY()));
        double startX = Math.clamp(Math.min(x, endX), left, right);
        double startY = Math.clamp(Math.min(y, endY), top, bottom);
        return new Rectangle2D(startX, startY, Math.clamp(Math.max(x, endX), left, right) - startX,
                Math.clamp(Math.max(y, endY), top, bottom) - startY);
    }

    private void capture(Rectangle2D bounds, Function<Rectangle2D, NoteHandoff.Capture> payload) {
        try {
            var capture = payload.apply(bounds);
            if (capture == null) return;
            var encoded = NoteScreenshot.capture(pixels(bounds), capture);
            var content = new ClipboardContent();
            content.putString(capture.payload());
            content.putImage(new Image(new ByteArrayInputStream(encoded.png())));
            content.put(PNG, ByteBuffer.wrap(encoded.png()));
            boolean copied = Clipboard.getSystemClipboard().setContent(content);
            lastPng = encoded.png();
            reference = capture.reference();
            save.setVisible(true);
            status.accept(copied ? "Copied lasso · " + reference : "Clipboard unavailable · Save lasso PNG…");
        } catch (IOException | IllegalArgumentException failure) {
            status.accept("Could not capture lasso · " + failure.getMessage());
        }
    }

    private BufferedImage pixels(Rectangle2D bounds) {
        var window = view.canvas.getScene().getWindow();
        double sx = window.getRenderScaleX(), sy = window.getRenderScaleY();
        var parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(sx, sy));
        view.flush();
        var image = view.canvas.snapshot(parameters, null);
        int left = Math.clamp((int) Math.floor(bounds.getMinX() * sx), 0, (int) image.getWidth() - 1);
        int top = Math.clamp((int) Math.floor(bounds.getMinY() * sy), 0, (int) image.getHeight() - 1);
        int width = Math.min((int) image.getWidth(), (int) Math.ceil(bounds.getMaxX() * sx)) - left;
        int height = Math.min((int) image.getHeight(), (int) Math.ceil(bounds.getMaxY() * sy)) - top;
        var crop = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((java.awt.image.DataBufferInt) crop.getRaster().getDataBuffer()).getData();
        image.getPixelReader().getPixels(left, top, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return crop;
    }

    private void save() {
        var dialog = new FileChooser();
        dialog.setTitle("Save lasso PNG");
        dialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        dialog.setInitialFileName("opensmpsdeck-" + reference + ".png");
        var target = dialog.showSaveDialog(view.canvas.getScene().getWindow());
        if (target == null) return;
        try {
            Files.write(target.toPath(), lastPng, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            status.accept("Saved lasso · " + target.getName());
        } catch (IOException failure) {
            status.accept("Could not save lasso · " + failure.getMessage());
        }
    }

    private static DataFormat pngFormat() {
        var existing = DataFormat.lookupMimeType("image/png");
        return existing == null ? new DataFormat("image/png") : existing;
    }
}
