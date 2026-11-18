package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Capture the actual Studio scene at the current desktop scale. */
public class StudioDesignCapture {
    private static Stage stage;
    private static StudioWindow window;

    public static void main(String[] args) throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
        try {
            var scene = args.length > 3 ? args[3] : "arrangement";
            var source = ProjectFiles.load(Path.of(args[0])).project();
            var fixture = Path.of(args[1]);
            var renamed = new StudioProject(source.formatVersion(),
                    source.name() + " — Long project titles still leave room for playback and export controls",
                    source.mode(), source.ticksPerBeat(), source.tempo(), source.voices(), source.sequences(),
                    source.clips(), source.envelopes(), source.samples(), source.nativeProgram());
            if (scene.endsWith("commands")) renamed = withCommands(renamed, scene.startsWith("dense"));
            ProjectFiles.save(fixture, renamed, null);
            fx(() -> {
                stage = new Stage();
                window = new StudioWindow(stage, fixture);
                stage.show();
                if (scene.endsWith("commands")) {
                    window.showCommands();
                    window.commands.selectCommand("review-envelope");
                }
                return null;
            });
            if (source.nativeProgram() != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!fx(() -> window.nativeArrangement.timeline.project != null) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                if (!fx(() -> window.nativeArrangement.timeline.project != null))
                    throw new IllegalStateException("Native arrangement did not load");
            }
            fx(() -> {
                capture(Path.of(args[2]));
                if (source.nativeProgram() != null) {
                    var tabs = (TabPane) window.scene().lookup("#native-program-tabs");
                    tabs.getSelectionModel().select(1);
                    capture(Path.of(args[2] + ".bank.png"));
                }
                return null;
            });
        } finally {
            fx(() -> {
                if (window != null) window.close();
                if (stage != null) stage.close();
                Platform.exit();
                return null;
            });
        }
    }

    private static StudioProject withCommands(StudioProject project, boolean dense) {
        var envelope = new Envelope("review-vibrato", "Slow vibrato", Envelope.MODULATION, List.of(0, 1, 0xFF, 0x81));
        // The signed envelope program needs v4. Adding the first timed command
        // upgrades the source before its final asset bytes are applied.
        var introduced = new Envelope(envelope.id(), envelope.name(), envelope.type(), List.of(0, 1, 0x81));
        var sequence = project.sequences().getFirst().id();
        project = ProjectEdits.addEnvelope(project, introduced);
        project = ProjectEdits.addCommand(project, sequence, new CommandEvent("review-envelope", 6,
                NativeCommand.MODULATION_ENVELOPE, List.of(), envelope.id()));
        project = ProjectEdits.updateEnvelope(project, envelope);
        if (dense) {
            for (int i = 0; i < 12; i++) {
                project = ProjectEdits.addCommand(project, sequence, new CommandEvent("review-volume-" + i, 6,
                        NativeCommand.VOLUME, List.of(127 - 4 * i)));
            }
        }
        return project;
    }

    private static void capture(Path imagePath) throws Exception {
        var root = window.scene().getRoot();
        root.applyCss();
        root.layout();
        var bar = root.lookup(".projectbar");
        var play = button(bar, "Play");
        var stop = button(bar, "Stop");
        var export = button(bar, "Export WAV");
        var actionBounds = export.localToScene(export.getBoundsInLocal());
        if (actionBounds.getMaxX() > window.scene().getWidth()) {
            throw new IllegalStateException("Export control is outside the viewport");
        }
        System.out.printf("%s %s width=%.0f bar=%.0f play-y=%.0f stop-y=%.0f export-y=%.0f%n",
                stage.getOutputScaleX(), imagePath.getFileName(), window.scene().getWidth(),
                bar.getLayoutBounds().getHeight(), play.localToScene(0, 0).getY(),
                stop.localToScene(0, 0).getY(), export.localToScene(0, 0).getY());
        var apply = root.lookup("#apply-command");
        if (apply != null && apply.getScene() != null && apply.localToScene(0, 0).getY() > 0) {
            var viewport = ((ScrollPane) window.commands.getCenter()).lookup(".viewport");
            var bounds = apply.localToScene(apply.getBoundsInLocal());
            var visible = viewport.localToScene(viewport.getBoundsInLocal());
            System.out.printf("Apply command bottom %.0f, viewport bottom %.0f%n", bounds.getMaxY(), visible.getMaxY());
        }
        WritableImage fxImage = window.scene().snapshot(null);
        int width = (int) fxImage.getWidth(), height = (int) fxImage.getHeight();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var reader = fxImage.getPixelReader();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, reader.getArgb(x, y));
        ImageIO.write(image, "png", imagePath.toFile());
    }

    private static Button button(Node node, String text) {
        if (node instanceof Button button && button.isVisible() && button.getText().contains(text)) return button;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = button(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
