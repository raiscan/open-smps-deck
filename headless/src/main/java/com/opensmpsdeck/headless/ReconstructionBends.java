package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Attack-anchored pitch evidence from a mapped lead stem. */
final class ReconstructionBends {
    static final String REASON = "stem-backed-pitch-bend";
    private static final double HOP = .006;
    private static final double MIN_CHANGE = 70;
    private static final double MAX_EXCURSION = 1280;
    private static final double CENTS_PER_LOG = 1200 / Math.log(2);

    private ReconstructionBends() {}

    enum Kind { UP, DOWN, SCOOP_IN, FALL_OFF }

    record Point(double time, int cents) {}

    record Bend(Kind kind, double delay, double confidence, List<Point> points,
                @ReconstructionStages.Nullable String editReason, @ReconstructionStages.Nullable Boolean glide) {
        Bend(Kind kind, double delay, double confidence, List<Point> points, String editReason) {
            this(kind, delay, confidence, points, editReason, null);
        }
        Bend(Kind kind, double delay, double confidence, List<Point> points) {
            this(kind, delay, confidence, points, null);
        }
        Bend {
            points = List.copyOf(points);
        }

        Bend map(DoubleUnaryOperator clock) {
            double start = points.getFirst().time();
            return new Bend(kind, clock.applyAsDouble(start + delay) - clock.applyAsDouble(start), confidence,
                    points.stream().map(p -> new Point(clock.applyAsDouble(p.time()), p.cents())).toList(), editReason, glide);
        }

        String marker() {
            return kind == Kind.UP || kind == Kind.SCOOP_IN ? "↗" : "↘";
        }

        double cents(double time) {
            for (int i = 1; i < points.size(); i++) {
                Point a = points.get(i - 1), b = points.get(i);
                if (time <= b.time()) {
                    double fraction = Math.clamp((time - a.time()) / (b.time() - a.time()), 0, 1);
                    return a.cents() + fraction * (b.cents() - a.cents());
                }
            }
            return points.getLast().cents();
        }
    }

    private record Peak(double hz, double amplitude) {}
    record Sample(double time, double cents, double support) {}

    static Bend detect(ReferenceAudio audio, Note note) {
        return new Tracker(audio).detect(note);
    }

    record Approach(double start, double landing, List<Sample> samples, boolean strong) {
        Bend bend(double start, double end) {
            var points = new ArrayList<Point>();
            points.add(new Point(start, (int) Math.round(samples.getFirst().cents())));
            double previous = samples.getFirst().cents();
            for (var sample : samples) {
                if (sample.time() <= start || sample.time() >= end) continue;
                previous = Math.max(previous, Math.min(0, sample.cents()));
                points.add(new Point(sample.time(), (int) Math.round(previous)));
            }
            points.add(new Point(end, 0));
            var reduced = new ArrayList<Point>();
            reduced.add(points.getFirst());
            simplify(points, 0, points.size() - 1, reduced);
            return new Bend(Kind.SCOOP_IN, 0, strong ? .95 : .82, reduced);
        }
    }

    static final class Tracker {
        private final ReferenceAudio audio;
        private final LeadEffectMatcher.Spectrum spectrum = new LeadEffectMatcher.Spectrum();

        Tracker(ReferenceAudio audio) {
            this.audio = audio;
        }

        Bend detect(Note note) {
            return classify(note, trace(note));
        }

        List<Sample> trace(Note note) {
            double span = note.end() - note.start();
            if (note.run() != null || note.pitch() < 48 || note.pitch() > 94 || span < .075 || span > 2.5)
                return List.of();
            if (note.motion() != null && note.motion().vibrato() >= ReconstructionLeadAudio.MODULATED)
                return List.of();

            double carrier = ReconstructionLeadAudio.hz(note.pitch());
            int rate = audio.sampleRate();
            int length = (int) Math.round(rate * Math.clamp(7 / carrier, .016, .034));
            double[] window = new double[length];
            for (int i = 0; i < length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
            int first = (int) Math.ceil(note.start() * rate);
            int end = Math.min(audio.samples().length, (int) Math.floor(note.end() * rate));
            int hop = (int) Math.round(HOP * rate);
            var track = new ArrayList<Sample>();
            for (int at = first; at + length <= end; at += hop) {
                double time = (at + length / 2.0) / rate;
                double prior = track.isEmpty() ? 0 : track.getLast().cents();
                Sample sample = sample(at, window, carrier, time, prior, track.isEmpty(), .62, .14);
                if (sample == null) {
                    if (track.isEmpty() && time - note.start() < .023) continue;
                    // A broken ridge cannot support a bend through a new attack or silence.
                    if ((end - at) / (double) rate > .032) return List.of();
                    break;
                }
                track.add(sample);
            }
            return track;
        }

        /**
         * Track backward from the written landing. With the ridge seeded on the
         * settled pitch, the onset does not have to resemble the MIDI note.
         */
        Approach approach(Note note) {
            if (note.run() != null || note.pitch() < 48 || note.pitch() > 94 || note.end() - note.start() < .068)
                return null;
            double carrier = ReconstructionLeadAudio.hz(note.pitch());
            int rate = audio.sampleRate();
            int length = (int) Math.round(rate * Math.clamp(5 / carrier, .013, .022));
            double[] window = new double[length];
            for (int i = 0; i < length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
            var reverse = new ArrayList<Sample>();
            double last = Math.min(note.start() + .210, note.end() - .018);
            double first = Math.max(length / (2.0 * rate), note.start() - .55);
            double prior = 0;
            int stable = 0;
            for (double t = last; t >= first; t -= HOP) {
                int at = (int) Math.round(t * rate) - length / 2;
                if (at < 0 || at + length > audio.samples().length) return null;
                Sample sample = sample(at, window, carrier, t, prior, reverse.isEmpty(), .47, .14);
                if (sample == null || Math.abs(sample.cents() - prior) > 125) break;
                if (reverse.size() < 4 && Math.abs(sample.cents()) > 60) return null;
                if (sample.cents() > 65) break;
                reverse.add(sample);
                stable = Math.abs(sample.cents() - prior) < 11 ? stable + 1 : 0;
                prior = sample.cents();
                // Stop at the previous held note, even when there was no gap.
                if (sample.cents() < -70 && stable >= 4
                        && Math.abs(sample.cents() - reverse.get(reverse.size() - 5).cents()) < 26) {
                    reverse.subList(reverse.size() - 4, reverse.size()).clear();
                    break;
                }
            }
            return approach(note, reverse.reversed());
        }

        /** Look for the same motion in another written voice, even at a weak harmonic. */
        Approach parallelApproach(Note note, Approach guide) {
            double carrier = ReconstructionLeadAudio.hz(note.pitch());
            int rate = audio.sampleRate();
            int length = (int) Math.round(rate * Math.clamp(12 / carrier, .022, .036));
            double[] window = new double[length];
            for (int i = 0; i < length; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
            List<Sample> best = List.of();
            double bestError = Double.POSITIVE_INFINITY;
            // Allow a small strum offset. Compare the rise itself and two
            // settled frames to distinguish a parallel voice from a crossing.
            var expected = new ArrayList<>(guide.samples());
            for (int i = 1; i <= 2; i++) expected.add(new Sample(guide.landing() + i * HOP, 0, 1));
            for (int lag = -2; lag <= 3; lag++) {
                var measured = new ArrayList<Sample>();
                double error = 0;
                int gap = 0;
                for (var point : expected) {
                    double time = point.time() + lag * HOP;
                    int at = (int) Math.round(time * rate) - length / 2;
                    var sample = at < 0 || at + length > audio.samples().length ? null
                            : sample(at, window, carrier, time, point.cents(), true, .085, .055);
                    double tolerance = Math.abs(point.cents()) < 42 ? 48 : 70;
                    if (sample == null || Math.abs(sample.cents() - point.cents()) > tolerance) {
                        error += tolerance;
                        if (++gap > 2) break;
                        continue;
                    }
                    gap = 0;
                    error += Math.abs(sample.cents() - point.cents());
                    measured.add(sample);
                }
                if (measured.size() < expected.size() * .80 || gap != 0
                        || measured.getFirst().time() > guide.start() + (lag + 1.5) * HOP
                        || measured.getFirst().cents() > -70 || error >= bestError) continue;
                best = List.copyOf(measured);
                bestError = error;
            }
            if (best.isEmpty()) return null;
            var moving = new ArrayList<>(best.subList(0, best.size() - 2));
            var landing = moving.removeLast();
            moving.add(new Sample(landing.time(), 0, landing.support()));
            return new Approach(moving.getFirst().time(), landing.time(), List.copyOf(moving), true);
        }

        private static Approach approach(Note note, List<Sample> track) {
            if (track.size() < 7) return null;
            int first = 0;
            for (int i = 1; i < Math.min(3, track.size()); i++) {
                if (track.get(i).cents() < track.get(first).cents() - 18) first = i;
            }
            int landing = first + 1;
            int settled = 0;
            for (int i = landing; i < track.size(); i++) {
                settled = Math.abs(track.get(i).cents()) <= 42 ? settled + 1 : 0;
                if (settled == 4) {
                    landing = i - 3;
                    break;
                }
            }
            if (settled < 4 || landing - first < 3) return null;
            double time = track.get(landing).time();
            double start = track.get(first).time();
            double depth = -track.get(first).cents();
            if (depth < 65 || time - start < .017 || time - start > .55
                    || time < note.start() - .055 || time > note.start() + .17) return null;

            double back = 0, support = track.get(first).support(), largestStep = 0;
            int flat = 0, longestFlat = 0;
            for (int i = first + 1; i <= landing; i++) {
                // Evaluate the measured crossing too. A jump into the target
                // followed by a hold cannot stand in for a continuous ramp.
                double change = midpoint(track, i) - midpoint(track, i - 1);
                back += Math.max(0, -change);
                largestStep = Math.max(largestStep, change);
                flat = Math.abs(change) < 7 ? flat + 1 : 0;
                longestFlat = Math.max(longestFlat, flat);
                support += track.get(i).support();
            }
            if (back > Math.max(22, depth * .19) || longestFlat > 3 || largestStep > depth * .68) return null;
            boolean strong = depth >= 85 && time - start >= .027 && back <= Math.max(12, depth * .09)
                    && longestFlat < 3 && largestStep <= depth * .47 && support / (landing - first + 1) >= .86;
            var samples = new ArrayList<>(track.subList(first, landing));
            var last = track.get(landing);
            samples.add(new Sample(last.time(), 0, last.support()));
            return new Approach(start, time, List.copyOf(samples), strong);
        }

        private Sample sample(int at, double[] window, double carrier, double time, double prior, boolean attack,
                              double minimumSupport, double minimumFundamental) {
            double energy = 0;
            for (int i = 0; i < window.length; i++) {
                double value = audio.samples()[at + i];
                energy += value * value * window[i];
            }
            double rms = Math.sqrt(2 * energy / window.length);
            if (rms < .003) return null;

            spectrum.transform(audio.samples(), at, window);
            var peaks = new ArrayList<Peak>();
            double binHz = audio.sampleRate() / (double) spectrum.real.length;
            double floor = Math.max(.001, rms * .055) * window.length / 4;
            double left = spectrum.power(0), middle = spectrum.power(1);
            int lastBin = Math.min(spectrum.real.length / 2 - 1, (int) (carrier * 8.6 / binHz));
            for (int bin = 1; bin <= lastBin; bin++) {
                double right = spectrum.power(bin + 1);
                if (middle >= floor * floor && middle > left && middle > right) {
                    double a = Math.log(Math.max(left, 1e-20)), b = Math.log(middle);
                    double c = Math.log(Math.max(right, 1e-20));
                    double hz = (bin + .5 * (a - c) / (a - 2 * b + c)) * binHz;
                    peaks.add(new Peak(hz, 4 * Math.sqrt(middle) / window.length));
                }
                left = middle;
                middle = right;
            }

            Sample best = null;
            double bestSalience = 0;
            for (Peak peak : peaks) {
                double cents = CENTS_PER_LOG * Math.log(peak.hz() / carrier);
                double jump = Math.abs(cents - prior);
                if (jump > (attack ? 85 : 190) || Math.abs(cents) > MAX_EXCURSION
                        || peak.amplitude() < minimumFundamental * rms) continue;
                double salience = peak.amplitude();
                double numerator = cents * peak.amplitude();
                double harmonicEnergy = peak.amplitude() * peak.amplitude();
                for (int h = 2; h <= 4; h++) {
                    Peak partial = partial(peaks, peak.hz() * h);
                    if (partial == null) continue;
                    double weight = Math.min(peak.amplitude(), partial.amplitude()) / Math.sqrt(h);
                    salience += weight;
                    numerator += weight * CENTS_PER_LOG * Math.log(partial.hz() / (h * carrier));
                    harmonicEnergy += partial.amplitude() * partial.amplitude();
                }
                double support = Math.sqrt(harmonicEnergy) / rms;
                // A strong fundamental alone can support a nearly sinusoidal tone.
                if (support < minimumSupport) continue;
                double merit = salience / (1 + jump / 150);
                if (merit > bestSalience) {
                    bestSalience = merit;
                    best = new Sample(time, numerator / salience, Math.min(1, support));
                }
            }
            return best;
        }
    }

    private static Peak partial(List<Peak> peaks, double hz) {
        Peak best = null;
        double distance = .024 * hz;
        for (Peak peak : peaks) {
            double error = Math.abs(peak.hz() - hz);
            if (error < distance) {
                best = peak;
                distance = error;
            }
        }
        return best;
    }

    static Bend classify(Note note, List<Sample> track) {
        if (track.size() < 9) return null;
        double anchor = track.getFirst().cents();
        double end = midpoint(track, track.size() - 2);
        double change = end - anchor;
        if (Math.abs(change) < MIN_CHANGE) return null;
        int sign = change >= 0 ? 1 : -1;
        double forward = 0, back = 0, support = 0;
        double prior = anchor;
        var points = new ArrayList<Point>();
        points.add(new Point(note.start(), 0));
        double delay = Double.NaN;
        int lastMoving = 0;
        for (int i = 0; i < track.size(); i++) {
            Sample sample = track.get(i);
            double cents = midpoint(track, i);
            double difference = sign * (cents - prior);
            forward += Math.max(0, difference);
            back += Math.max(0, -difference);
            support += sample.support();
            if (sign * (cents - anchor) >= Math.max(5, Math.abs(change) * .05) && Double.isNaN(delay))
                delay = Math.max(0, sample.time() - HOP - note.start());
            if (sign * (end - cents) > Math.max(16, Math.abs(change) * .08)) lastMoving = i;
            points.add(new Point(sample.time(), (int) Math.round(cents - anchor)));
            prior = cents;
        }
        if (back > Math.max(14, forward * .19) || Double.isNaN(delay)
                || track.get(lastMoving).time() - note.start() - delay < .018) return null;
        double confidence = .55 * (support / track.size()) + .45 * (1 - back / forward);
        if (confidence < .80) return null;
        var last = track.getLast();
        if (note.end() > last.time()) points.add(new Point(note.end(), (int) Math.round(change)));
        Kind kind;
        if (sign > 0) kind = anchor < -45 && delay < .035 && Math.abs(end) < 40 ? Kind.SCOOP_IN : Kind.UP;
        else kind = delay > (note.end() - note.start()) * .62 && lastMoving >= track.size() - 7
                ? Kind.FALL_OFF : Kind.DOWN;
        if (kind == Kind.SCOOP_IN) {
            int attackCents = (int) Math.round(anchor);
            points.replaceAll(point -> new Point(point.time(), point.cents() + attackCents));
        }
        var reduced = new ArrayList<Point>();
        reduced.add(points.getFirst());
        simplify(points, 0, points.size() - 1, reduced);
        return new Bend(kind, delay, confidence, reduced);
    }

    private static double midpoint(List<Sample> track, int index) {
        double a = track.get(Math.max(0, index - 1)).cents(), b = track.get(index).cents();
        double c = track.get(Math.min(track.size() - 1, index + 1)).cents();
        return Math.clamp(b, Math.min(a, c), Math.max(a, c));
    }

    private static void simplify(List<Point> points, int from, int to, List<Point> result) {
        Point a = points.get(from), b = points.get(to);
        int split = -1;
        double worst = 14;
        for (int i = from + 1; i < to; i++) {
            Point point = points.get(i);
            double t = (point.time() - a.time()) / (b.time() - a.time());
            double error = Math.abs(point.cents() - (a.cents() + t * (b.cents() - a.cents())));
            if (error > worst) {
                worst = error;
                split = i;
            }
        }
        if (split < 0) result.add(b);
        else {
            simplify(points, from, split, result);
            simplify(points, split, to, result);
        }
    }
}
