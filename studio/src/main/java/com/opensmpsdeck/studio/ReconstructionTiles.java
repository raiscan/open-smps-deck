package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.PitchRange;
import com.opensmpsdeck.headless.ReconstructionInspection.Ridge;
import com.opensmpsdeck.headless.ReconstructionInspection.SpectrumTile;
import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
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

    /** Histogram bins per dB, and the levels the histogram covers. */
    private static final int BINS_PER_DB = 4, QUIETEST_DB = -150, LOUDEST_DB = 10;

    /** Counts a tile's finite levels above -150 dBFS in quarter-dB bins. */
    static int[] histogram(float[] levels) {
        var counts = new int[(LOUDEST_DB - QUIETEST_DB) * BINS_PER_DB];
        for (float value : levels) {
            if (!Float.isFinite(value) || value <= QUIETEST_DB) continue;
            counts[Math.min(counts.length - 1, (int) ((value - QUIETEST_DB) * BINS_PER_DB))]++;
        }
        return counts;
    }

    static Contrast autoContrast(List<float[]> levels) {
        return autoContrastFromHistograms(levels.stream().map(ReconstructionTiles::histogram).toList());
    }

    /**
     * Chooses a contrast from measured levels: the loudest half-percent reaches the top
     * and the quieter 40% sits at the bottom, so background stays dark and quiet stems fill the scale.
     * Tiles keep histograms, so this costs the same however many cells are visible.
     */
    static Contrast autoContrastFromHistograms(List<int[]> histograms) {
        if (histograms.isEmpty()) return null;
        var counts = new long[histograms.getFirst().length];
        long count = 0;
        for (var histogram : histograms)
            for (int bin = 0; bin < counts.length; bin++) {
                counts[bin] += histogram[bin];
                count += histogram[bin];
            }
        if (count < 64) return null;
        double top = level(counts, Math.min(count - 1, (long) (count * .995)));
        double bottom = level(counts, (long) (count * .40));
        int ceiling = (int) Math.clamp(Math.ceil(top), -80, 0);
        int range = (int) Math.clamp(Math.round(ceiling - bottom), 20, 85);
        return new Contrast(ceiling, range);
    }

    /** The centre level of the bin holding the value of the given rank. */
    private static double level(long[] counts, long rank) {
        long seen = 0;
        for (int bin = 0; bin < counts.length; bin++) {
            seen += counts[bin];
            if (seen > rank) return QUIETEST_DB + (bin + .5) / BINS_PER_DB;
        }
        return LOUDEST_DB;
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

    // About 2.5 kB each, so the cap covers the measured cache at its sparsest.
    private final Map<Key, int[]> histograms = Collections.synchronizedMap(lru(2048));
    private final Map<Key, Measurement> pending = new HashMap<>();
    private final Set<Key> failures = Collections.newSetFromMap(lru(64));
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4),
            runnable -> Thread.ofPlatform().daemon().name("reconstruction-spectrum").unstarted(runnable));
    // Colouring takes a fraction of a millisecond per tile, so one thread keeps up and never
    // waits behind measurements.
    private final java.util.concurrent.ExecutorService painters = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().daemon().name("reconstruction-colour").unstarted(runnable));
    private final Map<ImageKey, Future<?>> colouring = new HashMap<>();
    private record Layer(Key tile, int tint) {}
    private final Map<Layer, ImageKey> latest = lru(4096);
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

    /** Returns the tile coloured for this key, or null while it is measured or coloured. */
    Tile get(ImageKey key) {
        return displayed.get(key);
    }

    /**
     * Returns the tile coloured for this key, or the same measurement under the contrast it was
     * last coloured with. A contrast change then keeps the plot filled while workers recolour it.
     */
    Tile latest(ImageKey key) {
        var tile = displayed.get(key);
        if (tile != null) return tile;
        var previous = latest.get(new Layer(key.tile(), key.tint()));
        return previous == null ? null : displayed.get(previous);
    }

    private void show(ImageKey key, SpectrumTile data, int[] pixels) {
        displayed.put(key, new Tile(image(data, pixels), data.ridges()));
        latest.put(new Layer(key.tile(), key.tint()), key);
    }

    /** The level histogram of a measured tile, or null while it is not measured. */
    int[] histogram(Key key) {
        var cached = histograms.get(key);
        if (cached != null) return cached;
        var data = measured.get(key);
        if (data == null) return null;
        cached = histogram(data.dbfs());
        histograms.put(key, cached);
        return cached;
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
        // Octave blocks of one time slice share a measurement, so cancel it only
        // when none of its blocks is wanted any more.
        var dropped = new ArrayList<Measurement>();
        pending.entrySet().removeIf(entry -> {
            if (visibleMeasurements.contains(entry.getKey())) return false;
            dropped.add(entry.getValue());
            return true;
        });
        var wanted = Collections.newSetFromMap(new IdentityHashMap<Measurement, Boolean>());
        wanted.addAll(pending.values());
        for (var measurement : dropped) if (!wanted.contains(measurement)) measurement.future.cancel(false);
        colouring.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().cancel(false);
            return true;
        });
        var groups = new LinkedHashMap<Slice, List<ImageKey>>();
        for (var imageKey : visible) {
            var key = imageKey.tile();
            if (displayed.containsKey(imageKey) || colouring.containsKey(imageKey) || failures.contains(key)) continue;
            var data = measured.get(key);
            if (data != null) {
                colouring.put(imageKey, painters.submit(() -> colour(imageKey, data)));
                continue;
            }
            // A measurement already under way is coloured by the next request after it lands.
            if (pending.containsKey(key)) continue;
            groups.computeIfAbsent(new Slice(key.source(), key.level(), key.index(), key.rows(), key.reassigned()),
                    ignored -> new ArrayList<>()).add(imageKey);
        }
        for (var group : groups.values()) {
            var measurement = new Measurement();
            for (var imageKey : group) pending.put(imageKey.tile(), measurement);
            measurement.future = workers.submit(() -> prepare(group, measurement));
        }
    }

    private void colour(ImageKey key, SpectrumTile data) {
        var pixels = colors(data, key);
        Platform.runLater(() -> {
            if (closed) return;
            colouring.remove(key);
            show(key, data, pixels);
            repaint.run();
        });
    }

    /** Tiles that differ only in octave block, which one pass over the audio measures together. */
    private record Slice(AudioSource source, int level, int index, int rows, boolean reassigned) {}

    /** Measures a slice's blocks and colours them for the requested contrasts and tints. */
    private void prepare(List<ImageKey> group, Measurement measurement) {
        try {
            var keys = group.stream().map(ImageKey::tile).distinct().toList();
            measure(keys);
            var spectra = new ArrayList<SpectrumTile>(group.size());
            var pixels = new ArrayList<int[]>(group.size());
            for (var imageKey : group) {
                var data = measured.get(imageKey.tile());
                spectra.add(data);
                pixels.add(data == null ? null : colors(data, imageKey));
            }
            Platform.runLater(() -> {
                if (closed) return;
                for (var key : keys) pending.remove(key, measurement);
                for (int i = 0; i < group.size(); i++)
                    if (spectra.get(i) != null) show(group.get(i), spectra.get(i), pixels.get(i));
                repaint.run();
            });
        } catch (Exception failure) {
            Platform.runLater(() -> {
                if (closed) return;
                boolean shown = false;
                for (var key : group.stream().map(ImageKey::tile).distinct().toList()) {
                    if (pending.remove(key, measurement)) {
                        failures.add(key);
                        shown = true;
                    }
                }
                if (shown) error.accept("Spectrogram unavailable · " + failure.getMessage());
            });
        }
    }

    /** Measures the octave blocks of one time slice in a single pass over its audio. */
    private void measure(List<Key> group) throws Exception {
        var missing = group.stream().filter(key -> !measured.containsKey(key)).toList();
        if (missing.isEmpty()) return;
        var first = missing.getFirst();
        var full = model.spectrum(first.source(), first.start(), first.end(), WIDTH,
                missing.stream().map(key -> new PitchRange(key.low(), key.high())).toList(),
                first.rows(), first.reassigned());
        for (int i = 0; i < missing.size(); i++) {
            var key = missing.get(i);
            var tile = full.get(i);
            // The edge rows have only one neighbour, so their peaks are not ridges.
            var ridges = tile.ridges().stream()
                    .filter(ridge -> ridge.pitch() >= key.low() && ridge.pitch() <= key.high()).toList();
            var spectrum = new SpectrumTile(tile.columns(), tile.rows(), tile.dbfs(), ridges);
            histograms.put(key, histogram(spectrum.dbfs()));
            measured.put(key, spectrum);
        }
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        painters.shutdownNow();
        colouring.clear();
        pending.clear();
    }
}
