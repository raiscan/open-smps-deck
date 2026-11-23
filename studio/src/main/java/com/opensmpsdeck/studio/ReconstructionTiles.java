package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.Ridge;
import com.opensmpsdeck.headless.ReconstructionInspection.SpectrumTile;
import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Caches measured tiles separately from display images, so a dB change only recolors pixels.
 *
 * <p>A tile covers a power-of-two slice of source time and one octave block of pitch.
 * Pitch zoom and pitch scroll therefore reuse measured tiles instead of measuring
 * the visible pitch range again.
 */
final class ReconstructionTiles implements AutoCloseable {
    static final int WIDTH = 192;
    static final int CEILING_DB = -8;
    static final int MIN_LEVEL = -2;
    static final int MAX_LEVEL = 7;
    static final int BLOCK = 12;

    record Key(AudioSource source, int level, int index, int block) {
        double span() { return ReconstructionTiles.span(level); }
        double start() { return index * span(); }
        double end() { return (index + 1) * span(); }
        int low() { return block * BLOCK; }
        int high() { return Math.min(127, block * BLOCK + BLOCK - 1); }
    }

    static Key key(AudioSource source, int level, int index, int block) {
        return new Key(source, level, index, block);
    }

    static double span(int level) { return Math.scalb(1.5, level); }

    /** The level whose tiles have at least one measured column per screen pixel. */
    static int level(double pixelsPerSecond) {
        return (int) Math.clamp(Math.floor(Math.log(WIDTH / (1.5 * pixelsPerSecond)) / Math.log(2)), MIN_LEVEL, MAX_LEVEL);
    }

    static int block(int pitch) { return Math.clamp(pitch, 0, 127) / BLOCK; }

    record ImageKey(Key tile, int dbRange) {}
    record Tile(WritableImage image, List<Ridge> ridges) {}

    private final ReconstructionInspection model;
    private final Runnable repaint;
    private final Consumer<String> error;
    private final Map<Key, SpectrumTile> measured = Collections.synchronizedMap(lru(512));
    private final Map<ImageKey, Tile> displayed = lru(320);
    private final Map<ImageKey, Future<?>> pending = new HashMap<>();
    private final Set<ImageKey> failures = Collections.newSetFromMap(lru(64));
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4),
            runnable -> Thread.ofPlatform().daemon().name("reconstruction-spectrum").unstarted(runnable));
    private boolean closed;

    ReconstructionTiles(ReconstructionInspection model, Runnable repaint, Consumer<String> error) {
        this.model = model;
        this.repaint = repaint;
        this.error = error;
    }

    private static <K, V> Map<K, V> lru(int capacity) {
        return new LinkedHashMap<>(capacity, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> entry) { return size() > capacity; }
        };
    }

    Tile get(ImageKey key) { return displayed.get(key); }

    void request(Set<ImageKey> visible) {
        pending.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().cancel(false);
            return true;
        });
        for (var key : visible) {
            if (displayed.containsKey(key) || pending.containsKey(key) || failures.contains(key)) continue;
            pending.put(key, workers.submit(() -> prepare(key)));
        }
    }

    private void prepare(ImageKey imageKey) {
        var key = imageKey.tile();
        try {
            var spectrum = measured.get(key);
            if (spectrum == null) {
                var full = model.spectrum(key.source(), key.start(), key.end(), WIDTH, key.low(), key.high());
                // The edge rows have only one neighbour, so their peaks are not ridges.
                var ridges = full.ridges().stream()
                        .filter(ridge -> ridge.pitch() >= key.low() && ridge.pitch() <= key.high()).toList();
                spectrum = new SpectrumTile(full.columns(), full.rows(), full.dbfs(), ridges);
                measured.put(key, spectrum);
            }
            var data = spectrum;
            int[] pixels = new int[data.dbfs().length];
            for (int i = 0; i < pixels.length; i++) {
                double level = Math.clamp(1 + (data.dbfs()[i] - CEILING_DB) / imageKey.dbRange(), 0, 1);
                double energy = level * level;
                int red = 12 + (int) (232 * energy);
                int green = 18 + (int) (181 * energy);
                int blue = 32 + (int) (101 * level);
                pixels[i] = 0xff000000 | red << 16 | green << 8 | blue;
            }
            Platform.runLater(() -> {
                if (closed) return;
                var image = new WritableImage(data.columns(), data.rows());
                image.getPixelWriter().setPixels(0, 0, data.columns(), data.rows(), PixelFormat.getIntArgbPreInstance(),
                        pixels, 0, data.columns());
                displayed.put(imageKey, new Tile(image, data.ridges()));
                pending.remove(imageKey);
                repaint.run();
            });
        } catch (Exception failure) {
            Platform.runLater(() -> {
                if (closed) return;
                failures.add(imageKey);
                if (pending.remove(imageKey) != null)
                    error.accept("Spectrogram unavailable · " + failure.getMessage());
            });
        }
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        pending.clear();
    }
}
