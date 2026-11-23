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
 * Caches measured tiles separately from display images, so a contrast change only recolors pixels.
 *
 * <p>A tile covers a power-of-two slice of source time and one octave block of pitch.
 * Pitch zoom and pitch scroll therefore reuse measured tiles instead of measuring
 * the visible pitch range again. Its row density follows the pitch zoom in powers of
 * two, so tiles draw near one row per device pixel and stay crisp without smoothing.
 */
final class ReconstructionTiles implements AutoCloseable {
    static final int WIDTH = 192;
    static final int CEILING_DB = -8;
    static final int MIN_LEVEL = -2;
    static final int MAX_LEVEL = 7;
    static final int BLOCK = 12;
    static final int MIN_ROWS = 2;
    static final int MAX_ROWS = 16;
    /** Cache budgets in cells, so dense tiles evict sooner than sparse ones. */
    private static final long MEASURED_CELLS = 8_000_000, DISPLAYED_CELLS = 6_000_000;

    /** Rows per semitone, measured mode and time slice of one cached measurement. */
    record Key(AudioSource source, int level, int index, int block, int rows, boolean reassigned) {
        double span() { return ReconstructionTiles.span(level); }
        double start() { return index * span(); }
        double end() { return (index + 1) * span(); }
        int low() { return block * BLOCK; }
        int high() { return Math.min(127, block * BLOCK + BLOCK - 1); }
    }

    static Key key(AudioSource source, int level, int index, int block) {
        return key(source, level, index, block, MIN_ROWS, false);
    }

    static Key key(AudioSource source, int level, int index, int block, int rows, boolean reassigned) {
        return new Key(source, level, index, block, rows, reassigned);
    }

    /** The smallest power-of-two row density that gives at least one row per device pixel. */
    static int rows(double devicePixelsPerSemitone) {
        int rows = MIN_ROWS;
        while (rows < MAX_ROWS && rows < devicePixelsPerSemitone) rows *= 2;
        return rows;
    }

    static double span(int level) { return Math.scalb(1.5, level); }

    /** The level whose tiles have at least one measured column per screen pixel. */
    static int level(double pixelsPerSecond) {
        return (int) Math.clamp(Math.floor(Math.log(WIDTH / (1.5 * pixelsPerSecond)) / Math.log(2)), MIN_LEVEL, MAX_LEVEL);
    }

    static int block(int pitch) { return Math.clamp(pitch, 0, 127) / BLOCK; }

    /** Maps {@code ceiling - range} to {@code ceiling} dBFS onto the colour scale. */
    record Contrast(int ceiling, int range) {
        int floor() { return ceiling - range; }
    }

    static final int OPAQUE = -1;
    static final int SOLO = -2;

    record ImageKey(Key tile, Contrast contrast, int tint) {
        ImageKey(Key tile, Contrast contrast) { this(tile, contrast, OPAQUE); }
    }
    record Tile(WritableImage image, List<Ridge> ridges) {}

    // Magma anchors: dark for quiet bins, bright for strong partials, evenly spaced in lightness.
    private static final int[][] MAGMA = {
            {0, 0, 4}, {28, 16, 68}, {79, 18, 123}, {129, 37, 129}, {181, 54, 122},
            {229, 80, 100}, {251, 135, 97}, {254, 194, 135}, {252, 253, 191}};
    private static final int[] PALETTE = new int[256];
    static {
        for (int i = 0; i < PALETTE.length; i++) {
            double at = i / 255.0 * (MAGMA.length - 1);
            int low = Math.min((int) at, MAGMA.length - 2);
            double t = at - low;
            int rgb = 0;
            for (int channel = 0; channel < 3; channel++)
                rgb = rgb << 8 | (int) Math.round(MAGMA[low][channel] + t * (MAGMA[low + 1][channel] - MAGMA[low][channel]));
            PALETTE[i] = 0xff000000 | rgb;
        }
    }

    static int color(double dbfs, Contrast contrast) {
        double level = Math.clamp((dbfs - contrast.floor()) / contrast.range(), 0, 1);
        return PALETTE[(int) Math.round(level * 255)];
    }

    /**
     * Chooses a contrast from measured levels: the loudest half-percent reaches the top
     * and the quieter 40% sits at the bottom, so background stays dark and quiet stems fill the scale.
     */
    static Contrast autoContrast(List<float[]> levels) {
        int total = 0;
        for (var tile : levels) total += tile.length;
        if (total == 0) return null;
        int stride = Math.max(1, total / 60_000);
        var samples = new float[total / stride + levels.size()];
        int count = 0, seen = 0;
        for (var tile : levels) {
            for (float value : tile) {
                if (seen++ % stride != 0) continue;
                if (Float.isFinite(value) && value > -150) samples[count++] = value;
            }
        }
        if (count < 64) return null;
        java.util.Arrays.sort(samples, 0, count);
        double top = samples[Math.min(count - 1, (int) (count * .995))];
        double bottom = samples[(int) (count * .40)];
        int ceiling = (int) Math.clamp(Math.ceil(top), -80, 0);
        int range = (int) Math.clamp(Math.round(ceiling - bottom), 20, 85);
        return new Contrast(ceiling, range);
    }

    private final ReconstructionInspection model;
    private final Runnable repaint;
    private final Consumer<String> error;
    private final Map<Key, SpectrumTile> measured = Collections.synchronizedMap(
            sized(MEASURED_CELLS, (SpectrumTile tile) -> tile.dbfs().length));
    private final Map<ImageKey, Tile> displayed = sized(DISPLAYED_CELLS,
            (Tile tile) -> (long) (tile.image().getWidth() * tile.image().getHeight()));
    private static final class Measurement {
        private Future<?> future;
    }

    private final Map<Key, Measurement> pending = new HashMap<>();
    private final Set<Key> failures = Collections.newSetFromMap(lru(64));
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

    /** Evicts least recently used entries once their total weight passes a budget. */
    private static <K, V> Map<K, V> sized(long budget, java.util.function.ToLongFunction<V> weight) {
        return new LinkedHashMap<>(64, .75f, true) {
            private long used;

            @Override public V put(K key, V value) {
                var previous = super.put(key, value);
                if (previous != null) used -= weight.applyAsLong(previous);
                used += weight.applyAsLong(value);
                var eldest = entrySet().iterator();
                while (used > budget && size() > 1 && eldest.hasNext()) {
                    var entry = eldest.next();
                    if (entry.getKey().equals(key)) continue;
                    used -= weight.applyAsLong(entry.getValue());
                    eldest.remove();
                }
                return previous;
            }

            @Override public V remove(Object key) {
                var removed = super.remove(key);
                if (removed != null) used -= weight.applyAsLong(removed);
                return removed;
            }
        };
    }

    /**
     * Returns a displayable tile. A tile already measured under another contrast is recolored here,
     * which is cheap, so contrast changes never blank the plot.
     */
    Tile get(ImageKey key) {
        var tile = displayed.get(key);
        if (tile != null) return tile;
        var data = measured.get(key.tile());
        if (data == null) return null;
        tile = new Tile(image(data, colors(data, key)), data.ridges());
        displayed.put(key, tile);
        return tile;
    }

    /** Measured dBFS levels for a tile, if they are cached. */
    float[] levels(Key key) {
        var data = measured.get(key);
        return data == null ? null : data.dbfs();
    }

    private static int[] colors(SpectrumTile data, ImageKey key) {
        int[] pixels = new int[data.dbfs().length];
        for (int i = 0; i < pixels.length; i++) {
            int base = color(data.dbfs()[i], key.contrast());
            pixels[i] = key.tint() == OPAQUE ? base : overlayColor(base, data.dbfs()[i], key);
        }
        return pixels;
    }

    private static int overlayColor(int base, float dbfs, ImageKey key) {
        double level = Math.clamp((dbfs - key.contrast().floor()) / key.contrast().range(), 0, 1);
        boolean solo = key.tint() == SOLO;
        // Reveal the mix through quiet bins. Keep the original ramp's lightness variation
        // and move its hue toward the stem swatch in stronger bins.
        double alpha = solo ? Math.min(1, level * 2) : .8 * level * Math.sqrt(level);
        int red = channel(base, key.tint(), 16, level, alpha);
        int green = channel(base, key.tint(), 8, level, alpha);
        int blue = channel(base, key.tint(), 0, level, alpha);
        return (int) (255 * alpha) << 24 | red << 16 | green << 8 | blue;
    }

    private static int channel(int base, int tint, int shift, double level, double alpha) {
        int value = base >> shift & 255;
        if (tint >= 0) value = (int) (.35 * value + .65 * (tint >> shift & 255) * level);
        return (int) (value * alpha);
    }

    private static WritableImage image(SpectrumTile data, int[] pixels) {
        var image = new WritableImage(data.columns(), data.rows());
        image.getPixelWriter().setPixels(0, 0, data.columns(), data.rows(), PixelFormat.getIntArgbPreInstance(),
                pixels, 0, data.columns());
        return image;
    }

    void request(Set<ImageKey> visible) {
        var visibleMeasurements = visible.stream().map(ImageKey::tile).collect(java.util.stream.Collectors.toSet());
        pending.entrySet().removeIf(entry -> {
            if (visibleMeasurements.contains(entry.getKey())) return false;
            entry.getValue().future.cancel(false);
            return true;
        });
        for (var imageKey : visible) {
            var key = imageKey.tile();
            if (displayed.containsKey(imageKey) || pending.containsKey(key) || failures.contains(key)) continue;
            if (measured.containsKey(key)) {
                get(imageKey);
                continue;
            }
            var measurement = new Measurement();
            pending.put(key, measurement);
            measurement.future = workers.submit(() -> prepare(key, measurement));
        }
    }

    private void prepare(Key key, Measurement measurement) {
        try {
            var full = model.spectrum(key.source(), key.start(), key.end(), WIDTH, key.low(), key.high(),
                    key.rows(), key.reassigned());
            // The edge rows have only one neighbour, so their peaks are not ridges.
            var ridges = full.ridges().stream()
                    .filter(ridge -> ridge.pitch() >= key.low() && ridge.pitch() <= key.high()).toList();
            measured.put(key, new SpectrumTile(full.columns(), full.rows(), full.dbfs(), ridges));
            Platform.runLater(() -> {
                if (closed) return;
                pending.remove(key, measurement);
                repaint.run();
            });
        } catch (Exception failure) {
            Platform.runLater(() -> {
                if (closed) return;
                if (pending.remove(key, measurement)) {
                    failures.add(key);
                    error.accept("Spectrogram unavailable · " + failure.getMessage());
                }
            });
        }
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        pending.clear();
    }
}
