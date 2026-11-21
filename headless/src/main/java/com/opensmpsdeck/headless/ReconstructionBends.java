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
                Sample sample = sample(at, window, carrier, time, prior, track.isEmpty());
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

        private Sample sample(int at, double[] window, double carrier, double time, double prior, boolean attack) {
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
                        || peak.amplitude() < .14 * rms) continue;
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
                if (support < .62) continue;
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
