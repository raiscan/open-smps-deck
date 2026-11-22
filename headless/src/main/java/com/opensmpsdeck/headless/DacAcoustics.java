package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DacSample;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Rate-specific acoustic evidence derived from decoded DAC PCM. */
final class DacAcoustics {
    static final int VERSION = 2;
    static final int ANALYSIS_RATE = 24_000;
    private static final int SPECTRAL_RATE = ANALYSIS_RATE / 2;
    private static final int FFT_SIZE = ReconstructionRhythmAudio.FFT_SIZE;
    private static final int FRAME = 384; // 32 ms at the spectral rate
    private static final int HOP = 120;
    private static final int[] BAND_EDGES = {250, 500, 800, 1200, 1800, 2600, 3600, 5000};
    private static final double MIN_SYLLABLE_MS = 100;
    private static final double MIN_SYLLABLE_F0_SPREAD = .04; // About half a semitone of median deviation.
    private static final Set<Integer> VERIFIED_SPEECH_SLOTS = Set.of(0xA5, 0xA9, 0xAA, 0xBE);
    private static final String SPEECH_CURATION_SOURCE = "user listening 2026-11-22";

    enum SampleClass {
        SPEECH_LIKE, PITCHED_TONAL, NOISE_LIKE, MEMBRANE_BODY, UNCLASSIFIED;

        boolean pitched() { return this == SPEECH_LIKE || this == PITCHED_TONAL; }
    }

    record Profile(SampleClass sampleClass, double durationMs, double attackMs, double tailToPeak,
                   double harmonicity, double voicedFraction, @Nullable Double f0Hz, @Nullable Double f0SpreadOctaves,
                   double envelopeMovement, double upperFlatness, double lowEnergy, double upperEnergy) {}
    record RateProfile(int rate, String variantId, Profile profile) {}
    record AssetProfile(String assetId, String name, SampleClass sampleClass, boolean excludeFromDrumFit,
                        @Nullable String curationSource,
                        List<String> playedRoles, List<RateProfile> nativeRates) {}
    record Report(int profileVersion, List<AssetProfile> samples) {}
    private record Key(String assetId, int rate) {}
    private record Period(double hz, double correlation) {}
    private record Frame(double flatness, double low, double upper, double[] envelope, @Nullable Period period) {}

    private final Map<Key, Profile> profiles = new HashMap<>();
    private final Map<String, AssetProfile> assets = new LinkedHashMap<>();

    DacAcoustics(InstrumentCatalogue catalogue) {
        var variants = new LinkedHashMap<String, List<InstrumentCatalogue.Variant>>();
        for (var variant : catalogue.variants()) {
            if (catalogue.asset(variant.assetId()).kind() == InstrumentCatalogue.Kind.DAC_SAMPLE)
                variants.computeIfAbsent(variant.assetId(), ignored -> new ArrayList<>()).add(variant);
        }
        variants.forEach((id, sounds) -> {
            var asset = catalogue.asset(id);
            var lockedOnSlots = sounds.stream().flatMap(variant -> variant.occurrences().stream())
                    .filter(usage -> usage.slot() != null && "dac".equals(usage.table())
                            && CatalogueMatcher.LOCKED_ON_S3K.equals(usage.revision()))
                    .toList();
            var playedRoles = lockedOnSlots.stream().filter(InstrumentCatalogue.Occurrence::played)
                    .map(usage -> S3kDacFamily.slot(usage.slot())).filter(family -> family != S3kDacFamily.OTHER)
                    .map(Enum::name).distinct().sorted().toList();
            boolean curatedSpeech = lockedOnSlots.stream()
                    .anyMatch(usage -> "S3K".equalsIgnoreCase(usage.driver()) && VERIFIED_SPEECH_SLOTS.contains(usage.slot()));
            float[] pcm = pcm(asset.bytes());
            var rates = new LinkedHashMap<Integer, RateProfile>();
            for (var variant : sounds) {
                int rate = variant.recipe().rate();
                rates.computeIfAbsent(rate, key -> new RateProfile(key, variant.id(), profile(id, key,
                        ReconstructionDrums.resample(pcm, DacSample.sampleRateHz(key), ANALYSIS_RATE))));
            }
            var nativeRates = List.copyOf(rates.values());
            // A different pan or native rate must not let a speech or tonal
            // asset through by shortening its voiced portion during playback.
            SampleClass sampleClass = curatedSpeech ? SampleClass.SPEECH_LIKE
                    : nativeRates.stream().map(row -> row.profile().sampleClass())
                    .min(Comparator.naturalOrder()).orElse(SampleClass.UNCLASSIFIED);
            assets.put(id, new AssetProfile(id, asset.name(), sampleClass,
                    curatedSpeech || Boolean.TRUE.equals(asset.excludeFromDrumFit()),
                    curatedSpeech ? SPEECH_CURATION_SOURCE : null, playedRoles, nativeRates));
        });
    }

    Report report() {
        return new Report(VERSION, assets.values().stream().sorted(Comparator.comparing(AssetProfile::name)
                .thenComparing(AssetProfile::assetId)).toList());
    }

    AssetProfile asset(String id) { return assets.get(id); }

    Profile profile(String id, int rate, float[] audio) {
        return profiles.computeIfAbsent(new Key(id, rate), key -> analyze(audio, DacSample.sampleRateHz(rate)));
    }

    static Profile profile(DacSample slice) {
        double playbackHz = slice.sampleRateHz();
        float[] audio = ReconstructionDrums.resample(pcm(slice.pcm()), playbackHz, ANALYSIS_RATE);
        return analyze(audio, playbackHz);
    }

    static float[] pcm(byte[] bytes) {
        float[] pcm = new float[bytes.length];
        for (int i = 0; i < bytes.length; i++) pcm[i] = (Byte.toUnsignedInt(bytes[i]) - 128) / 128f;
        return pcm;
    }

    private static Profile analyze(float[] audio, double playbackHz) {
        var samples = new double[audio.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = (audio[2 * i] + audio[2 * i + 1]) / 2;
        int size = Math.min(FRAME, samples.length);
        int count = Math.max(1, (samples.length - size) / HOP + 1);
        var rms = new double[count];
        int peak = 0;
        for (int i = 0; i < count; i++) {
            rms[i] = deviation(samples, i * HOP, size);
            if (rms[i] > rms[peak]) peak = i;
        }
        if (rms[peak] < 1e-5)
            return new Profile(SampleClass.UNCLASSIFIED, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0);

        var frames = new ArrayList<Frame>();
        int first = 0;
        while (rms[first] < .13 * rms[peak]) first++;
        int last = count - 1;
        while (rms[last] < .13 * rms[peak]) last--;
        for (int i = first; i <= last; i++) {
            if (rms[i] >= .13 * rms[peak]) frames.add(frame(samples, i * HOP, size, playbackHz));
        }
        var voiced = frames.stream().filter(f -> f.period() != null).toList();
        double[] pitches = voiced.stream().mapToDouble(frame -> frame.period().hz()).toArray();
        Double f0 = pitches.length == 0 ? null : ReconstructionDrums.median(pitches.clone());
        Double spread = f0 == null ? null : ReconstructionDrums.median(Arrays.stream(pitches)
                .map(hz -> Math.abs(Math.log(hz / f0) / Math.log(2))).toArray());
        double movement = 0;
        for (int i = 2; i < voiced.size(); i++) {
            var a = voiced.get(i - 2).envelope();
            var b = voiced.get(i).envelope();
            for (int band = 0; band < a.length; band++) movement += Math.abs(a[band] - b[band]) / 2;
        }
        movement /= Math.max(1, voiced.size() - 2);
        double duration = 1000.0 * ((last - first) * HOP + size) / SPECTRAL_RATE;
        double attack = 1000.0 * (peak - first) * HOP / SPECTRAL_RATE;
        double tail = rms[last] / rms[peak];
        double harmonicity = voiced.stream().mapToDouble(f -> f.period().correlation()).sum() / frames.size();
        double fraction = voiced.size() / (double) frames.size();
        double flatness = frames.stream().mapToDouble(Frame::flatness).average().orElseThrow();
        double low = frames.stream().mapToDouble(Frame::low).average().orElseThrow();
        double upper = frames.stream().mapToDouble(Frame::upper).average().orElseThrow();
        SampleClass sampleClass;
        if (spread != null && duration >= MIN_SYLLABLE_MS && harmonicity >= .32 && fraction >= .42
                && spread >= MIN_SYLLABLE_F0_SPREAD && spread <= .55 && movement >= .21 && low < .70) {
            sampleClass = SampleClass.SPEECH_LIKE;
        } else if (f0 != null && harmonicity >= .42 && fraction >= .62 && spread <= .11
                && (low < .78 || duration >= 320 || tail >= .45)) {
            // A bright, quickly decaying hit can ring at a stable pitch and
            // still have enough broadband energy for a hat or cymbal.
            boolean brightTransient = duration < MIN_SYLLABLE_MS && tail <= .30 && flatness >= .12 && upper >= .33;
            sampleClass = brightTransient ? SampleClass.NOISE_LIKE : SampleClass.PITCHED_TONAL;
        } else if (harmonicity < .27 && flatness >= .20 && upper >= .44) {
            sampleClass = SampleClass.NOISE_LIKE;
        } else {
            sampleClass = SampleClass.MEMBRANE_BODY;
        }
        return new Profile(sampleClass, duration, attack, tail, harmonicity, fraction, f0, spread, movement,
                flatness, low, upper);
    }

    private static double deviation(double[] samples, int start, int size) {
        if (size == 0) return 0;
        double sum = 0, squares = 0;
        for (int i = start; i < start + size; i++) {
            sum += samples[i];
            squares += samples[i] * samples[i];
        }
        return Math.sqrt(Math.max(0, squares / size - Math.pow(sum / size, 2)));
    }

    private static Frame frame(double[] signal, int start, int size, double playbackHz) {
        var real = new double[FFT_SIZE];
        var imaginary = new double[FFT_SIZE];
        var lowRate = new double[size / 3];
        double mean = 0;
        for (int i = 0; i < size; i++) mean += signal[start + i] / size;
        for (int i = 0; i < size; i++) {
            double value = signal[start + i] - mean;
            real[i] = value * (.5 - .5 * Math.cos(2 * Math.PI * i / (size - 1)));
            if (i / 3 < lowRate.length) lowRate[i / 3] += value / 3;
        }
        ReconstructionRhythmAudio.fft(real, imaginary);
        double sum = 0, low = 0, upper = 0, flatEnergy = 0, flatLog = 0;
        int flatBins = 0;
        var envelope = new double[BAND_EDGES.length - 1];
        int band = 0;
        for (int bin = 1; bin < FFT_SIZE / 2; bin++) {
            double hz = bin * (double) SPECTRAL_RATE / FFT_SIZE;
            double power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            sum += power;
            if (hz < 380) low += power;
            if (hz >= 1200) upper += power;
            if (hz >= 450 && hz <= Math.min(5000, .42 * playbackHz)) {
                flatEnergy += power;
                flatLog += Math.log(Math.max(1e-12, power));
                flatBins++;
            }
            while (band < envelope.length && hz >= BAND_EDGES[band + 1]) band++;
            if (band < envelope.length && hz >= BAND_EDGES[band]) envelope[band] += power;
        }
        double envelopeEnergy = Arrays.stream(envelope).sum();
        if (envelopeEnergy > 0) for (int i = 0; i < envelope.length; i++) envelope[i] /= envelopeEnergy;
        double flatness = flatEnergy <= 1e-12 ? 0 : Math.min(1, Math.exp(flatLog / flatBins) * flatBins / flatEnergy);
        return new Frame(flatness, low / sum, upper / sum, envelope, period(lowRate));
    }

    private static Period period(double[] samples) {
        int rate = SPECTRAL_RATE / 3;
        int first = rate / 800, last = Math.min(samples.length / 2, rate / 55);
        var correlation = new double[last + 1];
        double best = .52;
        for (int lag = first; lag <= last; lag++) {
            double xx = 0, xy = 0, yy = 0;
            for (int i = lag; i < samples.length; i++) {
                double x = samples[i], y = samples[i - lag];
                xx += x * x;
                xy += x * y;
                yy += y * y;
            }
            correlation[lag] = xx * yy <= 1e-12 ? 0 : xy / Math.sqrt(xx * yy);
            if (lag > first && correlation[lag - 1] >= correlation[lag]) best = Math.max(best, correlation[lag - 1]);
        }
        for (int lag = first + 1; lag < last; lag++) {
            double r = correlation[lag], left = correlation[lag - 1], right = correlation[lag + 1];
            if (r >= Math.max(.52, .87 * best) && r > left && r >= right) {
                double refine = .5 * (left - right) / (left + right - 2 * r);
                return new Period(rate / (lag + refine), r);
            }
        }
        return null;
    }
}
