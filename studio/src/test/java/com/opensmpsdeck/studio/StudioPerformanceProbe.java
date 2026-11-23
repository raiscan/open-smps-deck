package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.ProjectFiles;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Frame-time probe for Studio's canvases. It drives zoom, scroll, resize and
 * playhead scenarios one step per pulse and reports the measured frame times.
 *
 * <p>Run it with {@code -Djavafx.animation.fullspeed=true}, so a frame's time is its
 * real cost rather than the 60 Hz pulse cap:
 * <pre>
 * xvfb-run -a -s '-screen 0 1920x1080x24' java -Djavafx.animation.fullspeed=true \
 *   -cp studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar:studio/target/test-classes \
 *   com.opensmpsdeck.studio.StudioPerformanceProbe &lt;reconstruction-work-copy&gt; &lt;project.smps.json&gt; [report.tsv]
 * </pre>
 * The probe writes reconstruction journals, so pass a scratch copy of the work folder.
 */
final class StudioPerformanceProbe {
    /** Wall-clock frame times plus CPU per frame for the process, the FX thread and spectrum workers. */
    private record Result(String scenario, double[] frames, double processCpu, double fxCpu, double workerCpu, double renderCpu) {
        double percentile(double p) {
            var sorted = frames.clone();
            Arrays.sort(sorted);
            return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
        }
        double mean() { return Arrays.stream(frames).average().orElse(0); }
        double max() { return Arrays.stream(frames).max().orElse(0); }
    }

    private final List<Result> results = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
        var probe = new StudioPerformanceProbe();
        try {
            if (!args[0].equals("-")) probe.reconstruction(Path.of(args[0]));
            if (args.length > 1 && !args[1].equals("-")) probe.arrangement(Path.of(args[1]));
            probe.report(args.length > 2 ? Path.of(args[2]) : null);
        } finally {
            Platform.exit();
        }
        System.exit(0);
    }

    private void reconstruction(Path work) throws Exception {
        var stage = fx(Stage::new);
        var pane = fx(() -> new ReconstructionPane(stage, work));
        var scene = pane.stage().getScene();
        await(() -> scene.lookup("#reconstruction-canvas") != null, 60);
        fx(() -> { pane.stage().setWidth(1600); pane.stage().setHeight(1000); return null; });
        frames(10, ignored -> {});
        for (String workspace : List.of("detail", "roles")) {
            fx(() -> {
                var button = (ToggleButton) scene.lookup("#reconstruction-" + workspace + "-view");
                if (!button.isSelected()) button.fire();
                return null;
            });
            var view = fx(() -> (ReconstructionCanvas) scene.lookup("#reconstruction-canvas").getParent());
            var zoom = fx(() -> (Slider) scene.lookup("#reconstruction-zoom"));
            fx(() -> { zoom.setValue(0); return null; });
            await(view::spectrumReady, 60);
            frames(20, ignored -> {});
            capture(scene, workspace + "-fit");
            fx(() -> { zoom.setValue(5); view.center(pane.inspection().duration() * .4); return null; });
            await(view::spectrumReady, 60);
            frames(20, ignored -> {});
            capture(scene, workspace + "-zoom5");
            fx(() -> { zoom.setValue(0); return null; });
            frames(20, ignored -> {});
            String tag = workspace + " · ";
            // Wheel zoom in steps of a quarter level, anchored at the plot centre, then back out.
            measure(tag + "wheel zoom in", 36, frame -> wheel(view, 10, false));
            measure(tag + "wheel zoom out", 36, frame -> wheel(view, -10, false));
            fx(() -> { zoom.setValue(5); return null; });
            frames(30, ignored -> {});
            var hbar = view.scroll;
            measure(tag + "scroll at zoom 5", 150, frame -> hbar.setHvalue(Math.min(1, .2 + frame * .002)));
            measure(tag + "shift-wheel pitch zoom", 30, frame -> wheel(view, frame < 15 ? -20 : 20, true));
            ScrollBar pitch = view.pitchScroll;
            measure(tag + "pitch scroll", 60, frame -> pitch.setValue(pitch.getMin()
                    + (pitch.getMax() - pitch.getMin()) * (frame % 30) / 30.0));
            fx(() -> { view.fitPitch(); zoom.setValue(6); return null; });
            frames(30, ignored -> {});
            double duration = fx(() -> pane.inspection().duration());
            measure(tag + "playhead follow at zoom 6", 240, frame -> view.movePlayhead(
                    Math.min(duration, 20 + frame * 4 / 60.0), true));
            fx(() -> { zoom.setValue(MaxZoom.VALUE); return null; });
            frames(30, ignored -> {});
            measure(tag + "scroll at max zoom", 120, frame -> hbar.setHvalue(Math.min(1, .3 + frame * .0002)));
            fx(() -> { zoom.setValue(2); return null; });
            frames(30, ignored -> {});
            measure(tag + "window resize", 80, frame -> pane.stage().setWidth(1600 - 8 * (frame < 40 ? frame : 80 - frame)));
        }
        fx(() -> { pane.close(); stage.close(); return null; });
    }

    private static final class MaxZoom { static final double VALUE = ReconstructionCanvas.MAX_TIME_ZOOM; }

    private void arrangement(Path project) throws Exception {
        var copy = project.resolveSibling("probe-" + System.nanoTime() + ".smps.json");
        try {
            ProjectFiles.save(copy, ProjectFiles.load(project).project(), null);
            var stage = fx(Stage::new);
            var window = fx(() -> { var opened = new StudioWindow(stage, copy); stage.setWidth(1600); stage.setHeight(1000);
                stage.show(); return opened; });
            frames(30, ignored -> {});
            capture(stage.getScene(), "arrangement");
            var timeline = window.timeline;
            measure("arrangement · timeline scroll", 120, frame -> timeline.scroll.setHvalue((frame % 60) / 60.0));
            var piano = window.piano;
            measure("arrangement · piano roll scroll", 120, frame -> piano.scroll.setHvalue((frame % 60) / 60.0));
            measure("arrangement · piano roll vertical scroll", 120, frame -> piano.scroll.setVvalue((frame % 60) / 60.0));
            measure("arrangement · window resize", 80, frame -> stage.setWidth(1600 - 8 * (frame < 40 ? frame : 80 - frame)));
            fx(() -> { window.close(); stage.close(); return null; });
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    /** Saves a PNG of the scene when -Dprobe.capture names a folder. */
    private static void capture(Scene scene, String name) throws Exception {
        String folder = System.getProperty("probe.capture");
        if (folder == null) return;
        var image = fx(() -> scene.snapshot(null));
        var png = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < png.getHeight(); y++)
            for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, reader.getArgb(x, y));
        Files.createDirectories(Path.of(folder));
        javax.imageio.ImageIO.write(png, "png", Path.of(folder, name + ".png").toFile());
    }

    private static void wheel(ReconstructionCanvas view, double delta, boolean shift) {
        var plot = view.canvas;
        double x = (plot.getWidth() + 76) / 2, y = plot.getHeight() / 2;
        Event.fireEvent(plot, new ScrollEvent(ScrollEvent.SCROLL, x, y, x, y, shift, false, false, false,
                false, false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                new PickResult(plot, new javafx.geometry.Point3D(x, y, 0), 0)));
    }

    /** Runs one step per pulse and records the time between pulses in milliseconds. */
    private void measure(String scenario, int count, IntConsumer step) throws Exception {
        frames(5, ignored -> {});
        var before = cpu();
        var times = frames(count, step);
        var after = cpu();
        results.add(new Result(scenario, times, (after[0] - before[0]) / 1e6 / count,
                (after[1] - before[1]) / 1e6 / count, (after[2] - before[2]) / 1e6 / count, (after[3] - before[3]) / 1e6 / count));
        var result = results.getLast();
        System.out.printf(Locale.ROOT, "%-44s mean %6.2f  p95 %6.2f  max %6.2f ms | cpu/frame process %6.2f fx %6.2f render %6.2f spectrum %6.2f%n",
                scenario, result.mean(), result.percentile(.95), result.max(), result.processCpu(), result.fxCpu(),
                result.renderCpu(), result.workerCpu());
    }

    /** Process, FX-thread and spectrum-worker CPU time in nanoseconds. */
    private static long[] cpu() {
        var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long fx = 0, workers = 0, render = 0;
        for (var info : threads.dumpAllThreads(false, false)) {
            long time = threads.getThreadCpuTime(info.getThreadId());
            if (time < 0) continue;
            if (info.getThreadName().equals("JavaFX Application Thread")
                    || info.getThreadName().equals("GtkNativeMainLoopThread")) fx += time;
            if (info.getThreadName().startsWith("reconstruction-spectrum")) workers += time;
            if (info.getThreadName().startsWith("QuantumRenderer")) render += time;
        }
        return new long[]{os.getProcessCpuTime(), fx, workers, render};
    }

    private static double[] frames(int count, IntConsumer step) throws Exception {
        var done = new CompletableFuture<double[]>();
        Platform.runLater(() -> new AnimationTimer() {
            final double[] times = new double[count];
            long last = -1;
            int frame = -1;
            @Override public void handle(long now) {
                long at = System.nanoTime();
                if (frame >= 0) times[frame] = (at - last) / 1e6;
                last = at;
                if (++frame == count) {
                    stop();
                    done.complete(times);
                    return;
                }
                try {
                    step.accept(frame);
                } catch (RuntimeException failure) {
                    stop();
                    done.completeExceptionally(failure);
                }
            }
        }.start());
        return done.get(120, TimeUnit.SECONDS);
    }

    private void report(Path output) throws Exception {
        var lines = new ArrayList<String>();
        lines.add("scenario\tframes\tmean_ms\tp50_ms\tp95_ms\tmax_ms\tprocess_cpu_ms\tfx_cpu_ms\trender_cpu_ms\tspectrum_cpu_ms");
        for (var result : results) lines.add(String.format(Locale.ROOT, "%s\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f",
                result.scenario(), result.frames().length, result.mean(), result.percentile(.5),
                result.percentile(.95), result.max(), result.processCpu(), result.fxCpu(), result.renderCpu(), result.workerCpu()));
        if (output != null) Files.write(output, lines);
    }

    private static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!fx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Timed out waiting for the view");
            Thread.sleep(50);
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
