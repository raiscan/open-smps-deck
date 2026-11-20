package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Source-time pitch tracks for an aligned stem. */
final class ReconstructionLeadAudio {
    static final double HOP = .010;
    static final double MIN_TONE = .150;
    static final double MODULATED = .72;
    private static final int LOW = 36, HIGH = 93, HARMONICS = 4;
    private static final int MAX_FIT = 45, MIN_FIT = 15;
    private static final double WINDOW = .052;
    private static final double LEGATO_MIN_LEVEL = .003;
    private static final double LEGATO_RELATIVE_LEVEL = .35;
    private static final double CENTS = 1200 / Math.log(2);
    private static final double[] HARMONIC_OFFSETS = {0, 0, 12, 12 * Math.log(3) / Math.log(2), 24};
    private static final Model[][] MODELS = models();

    record Evidence(double vibrato, double glide) {
        static final Evidence NONE = new Evidence(0, 0);
        double confidence() { return Math.max(vibrato, .6 * glide); }
    }

    record Tone(double start, double end, Evidence evidence) {}

    private ReconstructionLeadAudio() {}

    static double hz(int pitch) { return 440 * Math.pow(2, (pitch - 69) / 12.0); }

    static final class Track {
        private final float[][] peaks = new float[(HIGH - LOW + 1) * HARMONICS][];
        private final float[][] cents = new float[peaks.length][];
        private final float[] rms;
        private final float[] mono;
        private final double rate;
        private final double duration;

        static Track read(Path wave, double duration) throws IOException {
            int rate = 24_000;
            int frames = (int) Math.floor(rate * Math.min(duration, ReferenceAudio.durationMicros(wave) / 1e6));
            var mono = new float[frames];
            for (int start = 0; start < frames; start += rate) {
                int length = Math.min(rate, frames - start);
                var stereo = ReferenceAudio.stereoRange(wave, rate, start, length);
                for (int i = 0; i < length; i++) mono[start + i] = (stereo[2 * i] + stereo[2 * i + 1]) / 2;
            }
            return new Track(new ReferenceAudio(mono, rate));
        }

        Track(ReferenceAudio audio) {
            int stride = Math.max(1, audio.sampleRate() / 12_000);
            rate = audio.sampleRate() / (double) stride;
            duration = audio.samples().length / (double) audio.sampleRate();
            rms = new float[(int) Math.ceil(duration / HOP)];
            for (int i = 0; i < peaks.length; i++) {
                peaks[i] = new float[rms.length];
                cents[i] = new float[rms.length];
            }
            mono = new float[audio.samples().length / stride];
            for (int i = 0; i < mono.length; i++) {
                for (int j = 0; j < stride; j++) mono[i] += audio.samples()[i * stride + j];
                mono[i] /= stride;
            }
            // The window resolves neighboring partials while retaining most 4–8 Hz motion.
            double[] window = new double[(int) Math.clamp(Math.round(WINDOW * rate), 3, 4096)];
            for (int i = 0; i < window.length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (window.length - 1));
            var spectrum = new LeadEffectMatcher.Spectrum();
            double binHz = rate / spectrum.real.length;
            double norm = 4.0 / window.length;
            double minimumHz = hz(LOW) * .96;
            for (int frame = 0; frame < rms.length; frame++) {
                int start = (int) Math.round(frame * HOP * rate) - window.length / 2;
                if (start < 0 || start + window.length > mono.length) continue;
                double energy = 0;
                for (int i = 0; i < window.length; i++) energy += mono[start + i] * mono[start + i] * window[i];
                rms[frame] = (float) Math.sqrt(2 * energy / window.length);
                if (rms[frame] < .0015) continue;
                spectrum.transform(mono, start, window);
                double left = 0, middle = spectrum.power(0);
                for (int k = 0; k + 1 < spectrum.real.length / 2; k++) {
                    double right = spectrum.power(k + 1);
                    if (k > 0 && k * binHz >= minimumHz && middle > left && middle > right) {
                        float amplitude = (float) (norm * Math.sqrt(middle));
                        if (amplitude >= Math.max(.0007, .023 * rms[frame])) {
                            double a = Math.log(Math.max(left, 1e-20)), b = Math.log(middle);
                            double c = Math.log(Math.max(right, 1e-20));
                            double frequency = (k + .5 * (a - c) / (a - 2 * b + c)) * binHz;
                            addPeak(frame, frequency, amplitude);
                        }
                    }
                    left = middle;
                    middle = right;
                }
            }
        }

        private void addPeak(int frame, double frequency, float amplitude) {
            double midi = 69 + CENTS / 100 * Math.log(frequency / 440);
            for (int h = 1; h <= HARMONICS; h++) {
                double fundamental = midi - HARMONIC_OFFSETS[h];
                int pitch = (int) Math.round(fundamental);
                if (pitch < LOW || pitch > HIGH || Math.abs(fundamental - pitch) > .44) continue;
                int index = index(pitch, h);
                if (amplitude <= peaks[index][frame]) continue;
                peaks[index][frame] = amplitude;
                cents[index][frame] = (float) (100 * (fundamental - pitch));
            }
        }

        Evidence evidence(int pitch, double start, double end, List<Note> competitors) {
            if (pitch < LOW || pitch > HIGH) return Evidence.NONE;
            int first = first(start), last = first(end);
            double vibrato = 0, sharedVibrato = 0, glide = 0;
            for (int h = 1; h <= HARMONICS; h++) {
                int index = index(pitch, h);
                for (int cursor = first; cursor + MIN_FIT <= last;) {
                    while (cursor < last && !supported(index, cursor)) cursor++;
                    int stop = cursor;
                    while (stop < last && supported(index, stop)) stop++;
                    for (int at = cursor; at + MIN_FIT <= stop; at += 8) {
                        int to = Math.min(at + MAX_FIT, stop);
                        boolean contested = shared(pitch, h, at * HOP, to * HOP, competitors);
                        if (contested && h != 1) continue;
                        var evidence = fit(cents[index], peaks[index], at, to);
                        if (contested) sharedVibrato = Math.max(sharedVibrato, .88 * evidence.vibrato);
                        else vibrato = Math.max(vibrato, evidence.vibrato);
                        if (!contested) glide = Math.max(glide, evidence.glide);
                    }
                    cursor = stop;
                }
            }
            return new Evidence(round(Math.max(vibrato, sharedVibrato)), round(glide));
        }

        Tone tone(int pitch, double start, double end, List<Note> competitors) {
            if (pitch < LOW || pitch > HIGH) return null;
            int cursor = first(start), last = first(end);
            Tone best = null;
            while (cursor + MIN_FIT <= last) {
                while (cursor < last && !present(pitch, cursor, 1)) cursor++;
                int stop = cursor, release = cursor;
                while (stop < last && stop - release <= 3) {
                    if (present(pitch, stop, .58)) release = stop + 1;
                    stop++;
                }
                if (release - cursor >= MIN_FIT) {
                    double from = Math.max(start, cursor * HOP);
                    double to = Math.min(end, release * HOP);
                    var evidence = evidence(pitch, from, to, competitors);
                    if (to - from + 1e-8 >= MIN_TONE && evidence.vibrato >= MODULATED
                            && (best == null || evidence.vibrato > best.evidence.vibrato)) {
                        best = new Tone(from, to, evidence);
                    }
                }
                cursor = Math.max(stop, cursor + 1);
            }
            return best;
        }

        double release(int pitch, double start, double end, double limit, List<Note> competitors) {
            if (pitch < LOW || pitch > HIGH) return end;
            int cursor = first(end), last = first(limit);
            int release = cursor;
            int index = index(pitch, 1);
            int anchor = first(Math.max(start, end - .060));
            if (cursor - anchor < 3 || !present(pitch, cursor - 1, 1)) return end;
            double level = 0;
            for (int i = anchor; i < cursor; i++) level = Math.max(level, peaks[index][i]);
            for (int i = cursor; i < last && i - release <= 2; i++) {
                if (present(pitch, i, .68) && peaks[index][i] >= .42 * level) release = i + 1;
            }
            // Require new motion after the cut, or strong motion straddling a short continuation.
            double to = Math.min(limit, release * HOP - .020);
            if (to - end < .050) return end;
            double from = Math.max(start + .020, end - .15);
            if (evidence(pitch, from, to, competitors).vibrato < MODULATED
                    || to - end >= .17 && evidence(pitch, end, to, competitors).vibrato < MODULATED)
                return end;
            return to;
        }

        private record Band(float[] peaks, double frequency, double floor) {}

        boolean bridges(Note prior, Note following, List<Note> competitors) {
            double end = prior.end(), next = following.start();
            if (next > duration || next <= end) return false;
            var outgoing = legatoBand(prior.pitch(), Math.max(prior.start(), end - .060), end, end, next, competitors);
            var incoming = prior.pitch() == following.pitch() ? null : legatoBand(following.pitch(), next,
                    Math.min(following.end(), next + .060), end, next, competitors);
            if (outgoing == null && incoming == null) return false;
            // A centered spectrum can see attacks on either side of a short real rest.
            // Corroborate short gaps with samples taken entirely between the two MIDI edges.
            if (next - end < WINDOW) {
                if (outgoing != null && gapAmplitude(outgoing.frequency, end, next) < outgoing.floor) outgoing = null;
                if (incoming != null && gapAmplitude(incoming.frequency, end, next) < incoming.floor) incoming = null;
            }
            int samples = Math.max(1, (int) Math.ceil((next - end) / HOP));
            for (int i = 0; i < samples; i++) {
                double center = end + (i + .5) * (next - end) / samples;
                double audible = .10 * at(rms, center);
                if (supports(outgoing, center, audible)) continue;
                outgoing = null; // Support can pass from the earlier pitch to the next pitch once.
                if (!supports(incoming, center, audible)) return false;
            }
            return true;
        }

        private static boolean supports(Band band, double center, double audible) {
            return band != null && at(band.peaks, center) >= Math.max(band.floor, audible);
        }

        private Band legatoBand(int pitch, double from, double to, double cut, double next, List<Note> competitors) {
            if (pitch < LOW || pitch > HIGH) return null;
            for (var other : competitors) {
                if (other.start() >= next || other.end() <= cut) continue;
                double ratio = hz(pitch) / hz(other.pitch());
                if (ratio >= .98 && ratio <= 4.02 && Math.abs(ratio - Math.rint(ratio)) < .02)
                    return null;
            }
            int index = index(pitch, 1);
            var fundamental = peaks[index];
            double anchor = 0;
            int strongest = 0;
            for (int i = first(from); i < first(to); i++) {
                if (fundamental[i] <= anchor) continue;
                anchor = fundamental[i];
                strongest = i;
            }
            if (anchor < LEGATO_MIN_LEVEL) return null;
            double frequency = hz(pitch) * Math.pow(2, cents[index][strongest] / 1200);
            return new Band(fundamental, frequency, Math.max(LEGATO_MIN_LEVEL, LEGATO_RELATIVE_LEVEL * anchor));
        }

        private double gapAmplitude(double frequency, double start, double end) {
            int first = (int) Math.ceil(start * rate), stop = Math.min(mono.length, (int) Math.ceil(end * rate));
            int samples = stop - first;
            if (samples * frequency < 2 * rate) return 0; // Fewer than two cycles cannot resolve the tone.
            double real = 0, imag = 0;
            for (int i = 0; i < samples; i++) {
                double phase = Math.TAU * frequency * i / rate;
                real += mono[first + i] * Math.cos(phase);
                imag += mono[first + i] * Math.sin(phase);
            }
            return 2 * Math.hypot(real, imag) / samples;
        }

        private static double at(float[] values, double seconds) {
            double position = seconds / HOP;
            int left = (int) position;
            if (left + 1 >= values.length) return 0;
            return values[left] + (values[left + 1] - values[left]) * (position - left);
        }

        boolean[] referenceActivity() {
            var sounding = new boolean[rms.length];
            for (int pitch = 55; pitch <= 84; pitch++) {
                // Fixed reference-only windows, independent of MIDI or reconstruction.
                for (int frame = 0; frame + 20 <= rms.length; frame += 10) {
                    int present = 0;
                    for (int i = frame; i < frame + 20; i++) if (present(pitch, i, 1)) present++;
                    if (present < 18) continue;
                    double from = frame * HOP, to = (frame + 20) * HOP;
                    if (evidence(pitch, from, to, List.of()).vibrato >= MODULATED)
                        Arrays.fill(sounding, frame, frame + 20, true);
                }
            }
            return sounding;
        }

        double duration() { return duration; }

        private int first(double seconds) { return Math.clamp((int) Math.ceil(seconds / HOP - 1e-7), 0, rms.length); }

        private boolean supported(int index, int frame) {
            return peaks[index][frame] >= Math.max(.0015, .045 * rms[frame]);
        }

        private boolean present(int pitch, int frame, double threshold) {
            return peaks[index(pitch, 1)][frame] >= threshold * Math.max(.003, .21 * rms[frame]);
        }
    }

    private static int index(int pitch, int harmonic) { return (pitch - LOW) * HARMONICS + harmonic - 1; }

    private static boolean shared(int pitch, int harmonic, double start, double end, List<Note> competitors) {
        double frequency = hz(pitch) * harmonic;
        for (var other : competitors) {
            if (other.pitch() == pitch || other.end() < start || other.start() > end) continue;
            double fundamental = hz(other.pitch());
            long h = Math.round(frequency / fundamental);
            if (h >= 1 && h <= HARMONICS && Math.abs(h * fundamental - frequency) < 25) return true;
        }
        return false;
    }

    private record Model(double[] sine, double[] cosine, double ss, double cc, double sc) {}

    private static Model[][] models() {
        var models = new Model[MAX_FIT + 1][];
        for (int size = MIN_FIT; size <= MAX_FIT; size++) {
            models[size] = new Model[17];
            for (int r = 0; r < models[size].length; r++) {
                double rate = 4 + r * .25;
                if (size * HOP * rate < .85) continue;
                var sine = new double[size];
                var cosine = new double[size];
                for (int i = 0; i < size; i++) {
                    double phase = Math.TAU * rate * HOP * i;
                    sine[i] = Math.sin(phase);
                    cosine[i] = Math.cos(phase);
                }
                detrend(sine);
                detrend(cosine);
                models[size][r] = new Model(sine, cosine, dot(sine, sine), dot(cosine, cosine), dot(sine, cosine));
            }
        }
        return models;
    }

    private static Evidence fit(float[] pitch, float[] level, int first, int end) {
        int size = end - first;
        var values = new double[size];
        var envelope = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = pitch[first + i];
            envelope[i] = Math.log(level[first + i]);
        }
        double drift = detrend(values);
        double energy = dot(values, values);
        double glide = Math.clamp((Math.abs(drift) - 15) / 25, 0, 1) *
                (1 - Math.min(1, Math.sqrt(energy / size) / 14));
        if (energy < 13 * size || Math.abs(drift) > 34) return new Evidence(0, glide);
        detrend(envelope);
        double envEnergy = dot(envelope, envelope);
        // Interfering partials often oscillate in amplitude as well as in frequency.
        if (envEnergy > .004 * size && Math.abs(dot(values, envelope)) > .60 * Math.sqrt(energy * envEnergy))
            return new Evidence(0, glide);
        double best = 0;
        for (var model : MODELS[size]) {
            if (model == null) continue;
            double ys = dot(values, model.sine), yc = dot(values, model.cosine);
            double determinant = model.ss * model.cc - model.sc * model.sc;
            double a = (ys * model.cc - yc * model.sc) / determinant;
            double b = (yc * model.ss - ys * model.sc) / determinant;
            double depth = Math.hypot(a, b);
            if (depth < 7 || depth > 33) continue;
            double es = dot(envelope, model.sine), ec = dot(envelope, model.cosine);
            double amplitudeMotion = Math.hypot(es * model.cc - ec * model.sc, ec * model.ss - es * model.sc)
                    / determinant;
            if (amplitudeMotion > .13) continue;
            double explained = (a * ys + b * yc) / energy;
            best = Math.max(best, Math.clamp(explained, 0, 1));
        }
        return new Evidence(best, glide);
    }

    private static double detrend(double[] values) {
        double center = (values.length - 1) / 2.0;
        double sum = 0, moment = 0, square = 0;
        for (int i = 0; i < values.length; i++) {
            double x = i - center;
            sum += values[i];
            moment += x * values[i];
            square += x * x;
        }
        double slope = moment / square;
        for (int i = 0; i < values.length; i++) values[i] -= sum / values.length + slope * (i - center);
        return 2 * center * slope;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private static double round(double value) { return Math.round(value * 1000) / 1000.0; }
}
