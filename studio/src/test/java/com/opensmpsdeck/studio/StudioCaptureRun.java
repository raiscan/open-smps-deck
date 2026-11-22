package com.opensmpsdeck.studio;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** JavaFX capture and bounded waits, shared by the design capture scenarios. */
final class StudioCaptureRun {
    record Size(int width, int height) {
        @Override public String toString() { return width + "x" + height; }
    }

    @FunctionalInterface interface Action { void run() throws Exception; }

    private final Path output;
    private final Size size;
    private final ArrayList<String> images = new ArrayList<>();
    private final ArrayList<String> missed = new ArrayList<>();

    StudioCaptureRun(Path output, Size size) {
        this.output = output;
        this.size = size;
    }

    void attempt(String surface, Action action) {
        try {
            action.run();
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            miss(surface, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    void miss(String surface, String reason) {
        missed.add("- **" + surface + "** — " + reason.replaceAll("\\R", " "));
    }

    void finish() throws Exception {
        String header = "# Studio design captures · " + size + "\n\n"
                + "| Surface | State | Window (logical px) | PNG (px) | Capture |\n"
                + "| --- | --- | --- | --- | --- |\n";
        Files.writeString(output.resolve("index.md"), header + String.join("\n", images)
                + "\n\n## Unreachable states\n\n" + (missed.isEmpty() ? "None." : String.join("\n", missed)) + "\n");
        System.out.printf("%s: %d PNGs, %d unreachable states%n", size, images.size(), missed.size());
        missed.forEach(System.out::println);
    }

    void resize(Scene scene) throws Exception {
        var window = scene.getWindow();
        ui(() -> {
            window.setX(0);
            window.setY(0);
            window.setWidth(size.width());
            window.setHeight(size.height());
        });
        if (Boolean.getBoolean("studio.capture.isolatedDisplay"))
            await("window resize to " + size, () -> scene.getWidth() == size.width() && scene.getHeight() == size.height());
    }

    void shot(String name, String surface, String state, Scene scene) throws Exception {
        var capture = fx(() -> {
            layout(scene);
            var image = scene.snapshot(null);
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
            var awt = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            awt.setRGB(0, 0, width, height, pixels, 0, width);
            var window = scene.getWindow();
            return new Capture(awt, new Size((int) window.getWidth(), (int) window.getHeight()));
        });
        write(name, surface, state, capture.image(), capture.window());
    }

    private record Capture(BufferedImage image, Size window) {}

    private void write(String name, String surface, String state, BufferedImage image, Size window) throws Exception {
        String filename = name + ".png";
        ImageIO.write(image, "png", output.resolve(filename).toFile());
        images.add("| " + surface + " | " + state + " | " + window + " | "
                + new Size(image.getWidth(), image.getHeight()) + " | [" + filename + "](" + filename + ") |");
    }

    /** Run a modal JavaFX action while the dialog services its nested event loop. */
    static void dialog(Action open, String idOrTitle, DialogAction inspect) throws Exception {
        Set<Window> earlier = fx(() -> Set.copyOf(Window.getWindows()));
        var action = enqueue(() -> { open.run(); return null; });
        try {
            await("dialog " + idOrTitle, () -> action.isDone() || dialogWindow(earlier, idOrTitle) != null);
            var window = fx(() -> dialogWindow(earlier, idOrTitle));
            if (window == null) {
                action.get();
                throw new IllegalStateException("Dialog did not open: " + idOrTitle);
            }
            inspect.run(window.getScene());
        } finally {
            ui(() -> Window.getWindows().stream().filter(window -> !earlier.contains(window)).toList()
                    .reversed().forEach(Window::hide));
        }
        action.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface interface DialogAction { void run(Scene scene) throws Exception; }

    private static Window dialogWindow(Set<Window> earlier, String idOrTitle) {
        return Window.getWindows().stream().filter(window -> !earlier.contains(window) && window.isShowing())
                .filter(window -> window.getScene() != null && (idOrTitle.equals(window.getScene().getRoot().getId())
                        || window instanceof Stage stage && idOrTitle.equals(stage.getTitle())))
                .findFirst().orElse(null);
    }

    /** Native GTK choosers have no JavaFX Scene. Capture the isolated screen with the chooser open. */
    void nativePicker(Scene owner, String name, String surface, String title, Action open) throws Exception {
        require(Boolean.getBoolean("studio.capture.isolatedDisplay"),
                "Native screenshot requires the isolated Xvfb command");
        // Check the X11 dependencies before entering the native modal loop.
        chooser("probe", title);
        screen();
        ui(() -> ((Stage) owner.getWindow()).toFront());
        var action = enqueue(() -> { open.run(); return null; });
        try {
            chooser("wait", title);
            write(name, surface, "Native directory chooser over Studio", screen(), size);
        } finally {
            if (!action.isDone()) chooser("dismiss", title);
            action.get(10, TimeUnit.SECONDS);
        }
    }

    private static void chooser(String action, String title) throws Exception {
        var process = new ProcessBuilder("python3", "-I", "tools/studio_capture_x11.py", action, title)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!process.waitFor(6, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out waiting for the native chooser");
        }
        require(process.exitValue() == 0, "Native chooser " + action + " failed: " + title);
    }

    private BufferedImage screen() throws Exception {
        // Read Xvfb directly. AWT screen capture can enter the desktop portal on Linux.
        var png = Files.createTempFile("studio-native-picker-", ".png");
        try {
            var capture = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-y",
                    "-f", "x11grab", "-draw_mouse", "0", "-video_size", size.toString(),
                    "-i", System.getenv("DISPLAY"), "-frames:v", "1", "-threads", "1", png.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            if (!capture.waitFor(5, TimeUnit.SECONDS)) {
                capture.destroyForcibly();
                throw new IllegalStateException("Timed out reading the Xvfb screen");
            }
            require(capture.exitValue() == 0, "ffmpeg could not capture the Xvfb screen");
            return ImageIO.read(png.toFile());
        } finally {
            Files.deleteIfExists(png);
        }
    }

    static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    static <T extends Node> T node(Scene scene, String id, Class<T> type) {
        layout(scene);
        var found = scene.lookup("#" + id);
        if (!type.isInstance(found)) throw new IllegalStateException("Control unavailable: " + id);
        return type.cast(found);
    }

    static ButtonBase button(Scene scene, String text) {
        layout(scene);
        return Stream.of(".button", ".toggle-button", ".check-box")
                .flatMap(selector -> scene.getRoot().lookupAll(selector).stream()).filter(ButtonBase.class::isInstance)
                .map(ButtonBase.class::cast).filter(node -> visible(node) && text.equals(node.getText()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Button unavailable: " + text));
    }

    static boolean visible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
        }
        return node.getScene() != null;
    }

    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    static void fire(ButtonBase button) {
        require(!button.isDisabled(), button.getText() + " is disabled");
        button.fire();
    }

    static void press(Scene scene, String id) { fire(node(scene, id, ButtonBase.class)); }

    static String label(Scene scene, String id) { return node(scene, id, Labeled.class).getText(); }

    static Node tab(TabPane tabs, String name) {
        var tab = tabs.getTabs().stream().filter(candidate -> candidate.getText().equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException("Tab unavailable: " + name));
        require(!tab.isDisabled(), "Tab disabled: " + name);
        tabs.getSelectionModel().select(tab);
        return tab.getContent();
    }

    static ScrollPane containingScroll(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof ScrollPane scroll) return scroll;
        }
        throw new IllegalStateException("Control is not in a scroll pane");
    }

    static void reveal(Node node) {
        var scene = node.getScene();
        layout(scene);
        var scroll = containingScroll(node);
        var contents = scroll.getContent();
        double height = contents.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
        double y = contents.sceneToLocal(node.localToScene(node.getBoundsInLocal())).getMinY();
        scroll.setVvalue(height <= 0 ? 0 : Math.clamp(y / height, 0, 1));
    }

    static void await(String description, Callable<Boolean> ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
            if (fx(ready)) return;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    static void ui(Action action) throws Exception { fx(() -> { action.run(); return null; }); }

    static <T> T fx(Callable<T> action) throws Exception { return enqueue(action).get(20, TimeUnit.SECONDS); }

    private static <T> FutureTask<T> enqueue(Callable<T> action) {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task;
    }
}
