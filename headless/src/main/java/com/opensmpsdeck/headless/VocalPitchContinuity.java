package com.opensmpsdeck.headless;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;

import static com.opensmpsdeck.headless.ReconstructionLeadAudio.HIGH;
import static com.opensmpsdeck.headless.ReconstructionLeadAudio.HOP;
import static com.opensmpsdeck.headless.ReconstructionLeadAudio.LOW;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Select a pitch path over the candidate syllable gates, in the unshifted stem register. */
final class VocalPitchContinuity {
    static final String DECISIONS = "vocalPitchDecisions";
    private static final int PITCHES = HIGH - LOW + 1;
    private static final double STRONG = .72;
    private static final double PHRASE_GAP = .35;
    private static final double PRIOR_WINDOW = 8;
    private static final int[] HARMONIC_INTERVALS = {12, 19, 24};

    record Decision(String noteId, double sourceStart, double sourceEnd, int observedPitch, Integer selectedPitch,
                    double observedConfidence, double selectedConfidence, String reason) {}
    record Result(List<Note> notes, List<Decision> decisions) {}
    private record Evidence(double[] support, double[] confidence, boolean quiet) {}

    private VocalPitchContinuity() {}

    static Result select(List<Note> notes, ReconstructionLeadAudio.Track track, int[] periodic,
                         String encoded, int[] syllables, double floor) {
        if (notes.isEmpty()) return new Result(notes, List.of());
        var bytes = Base64.getDecoder().decode(encoded);
        ReconstructionArtifacts.require(bytes.length % 2 == 0, "f0Cents10ms", "expected 16-bit samples");
        var contour = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var model = new double[Math.min(contour.remaining(), track.frames())];
        for (int i = 0; i < model.length; i++) model[i] = Short.toUnsignedInt(contour.get()) / 100.0;

        var evidence = new Evidence[notes.size()];
        var song = new double[PITCHES];
        for (int i = 0; i < notes.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("vocal pitch selection cancelled");
            var note = notes.get(i);
            var measured = measure(note, track, periodic, model, floor);
            evidence[i] = measured;
            addPrior(song, measured, duration(note));
        }
        int[] path = bestPath(notes, evidence, song);
        var output = new ArrayList<Note>();
        var decisions = new ArrayList<Decision>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int pitch = path[i];
            double observedConfidence = confidence(evidence[i], note.pitch());
            double selectedConfidence = confidence(evidence[i], pitch);
            boolean syllable = hasSyllable(syllables, note.start(), 2);
            var selected = pitch == note.pitch() ? note : new Note(note.id(), note.part(), note.start(),
                    note.end(), pitch, note.velocity(), note.source(), note.confidence());
            boolean articulated = syllable || track.clearAttack(selected);
            if (evidence[i].quiet && !articulated) {
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), null,
                        observedConfidence, selectedConfidence, "belowVocalFloor"));
                continue;
            }
            if (!output.isEmpty() && !hasSyllable(syllables, note.start(), 5)) {
                var previous = output.getLast();
                double gap = note.start() - previous.end();
                if (previous.pitch() == pitch && gap <= .06
                        && (gap <= .011 || !articulated && track.bridges(previous, selected, List.of()))) {
                    output.set(output.size() - 1, previous.withTiming(previous.start(), Math.max(previous.end(), selected.end())));
                    decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), pitch,
                            observedConfidence, selectedConfidence, "sameSyllableFragment"));
                    continue;
                }
            }
            if (duration(note) < .1 - 1e-8 && !(selectedConfidence >= .50 && articulated)) {
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), null,
                        observedConfidence, selectedConfidence, "shortWithoutArticulation"));
                continue;
            }
            if (pitch != note.pitch())
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), pitch,
                        observedConfidence, selectedConfidence, "pitchContinuity"));
            output.add(selected);
        }
        return new Result(List.copyOf(output), List.copyOf(decisions));
    }

    private static Evidence measure(Note note, ReconstructionLeadAudio.Track track, int[] periodic,
                                    double[] model, double floor) {
        var support = new double[PITCHES];
        var confidence = new double[PITCHES];
        int from = Math.clamp((int) Math.ceil((note.start() + .01) / HOP), 0, model.length);
        int to = Math.clamp((int) Math.ceil((note.end() - .01) / HOP), from, model.length);
        double frames = Math.max(1, to - from);
        int audible = 0;
        for (int frame = from; frame < to; frame++) {
            double level = track.frameLevel(frame);
            if (level >= .5 * floor) audible++;
            if (level < floor || model[frame] == 0 && periodic[frame] == 0) continue;
            for (int pitch = LOW; pitch <= HIGH; pitch++) {
                double modelMatch = match(pitch, model[frame]);
                double periodMatch = match(pitch, periodic[frame]);
                double fundamental = track.partialLevel(pitch, 1, frame) / level;
                double second = track.partialLevel(pitch, 2, frame) / level;
                double third = track.partialLevel(pitch, 3, frame) / level;
                double spectrum = Math.min(1, (fundamental + .25 * second + .35 * third) / .65);
                // A subharmonic candidate needs its own fundamental or odd partial.
                double oddSupport = third * Math.max(periodMatch, Math.min(1, fundamental / .15));
                double independent = Math.min(1, (fundamental + .50 * oddSupport) / .32);
                double harmonic = 0;
                for (int interval : HARMONIC_INTERVALS) {
                    int lower = pitch - interval;
                    if (lower < LOW) continue;
                    double lowerFundamental = track.partialLevel(lower, 1, frame) / level;
                    double lowerThird = track.partialLevel(lower, 3, frame) / level;
                    double lowerPeriod = match(lower, periodic[frame]);
                    harmonic = Math.max(harmonic, lowerPeriod * Math.min(1, (lowerFundamental + lowerThird) / .30));
                }
                int index = pitch - LOW;
                support[index] += (.42 * spectrum * independent + .34 * periodMatch * independent
                        + .30 * modelMatch) * (1 - .65 * harmonic) / frames;
                confidence[index] += modelMatch * (.72 * periodMatch + .28 * spectrum * independent)
                        * (1 - harmonic) / frames;
            }
        }
        return new Evidence(support, confidence, audible < .15 * frames && to > from);
    }

    private static double match(int pitch, double observed) {
        return Math.clamp(1.5 - Math.abs(pitch - observed), 0, 1);
    }

    private static void addPrior(double[] distribution, Evidence evidence, double weight) {
        for (int i = 0; i < PITCHES; i++)
            if (evidence.confidence[i] >= .55) distribution[i] += evidence.confidence[i] * weight;
    }

    private static int[] bestPath(List<Note> notes, Evidence[] evidence, double[] song) {
        // Leave a state for a transcriber pitch outside the stem analyser's range.
        final int states = PITCHES + 1;
        var previous = new double[states];
        var current = new double[states];
        var back = new int[notes.size()][states];
        var local = new double[PITCHES];
        double songPeak = Arrays.stream(song).max().orElse(0);
        int left = 0, right = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var observed = evidence[i];
            double certainty = confidence(observed, note.pitch());
            while (right < notes.size() && notes.get(right).start() <= note.start() + PRIOR_WINDOW) {
                addPrior(local, evidence[right], duration(notes.get(right)));
                right++;
            }
            while (notes.get(left).start() < note.start() - PRIOR_WINDOW) {
                addPrior(local, evidence[left], -duration(notes.get(left)));
                left++;
            }
            int repeat = certainty >= STRONG ? 0 : repeatPitch(notes, evidence, i);
            double localPeak = Arrays.stream(local).max().orElse(0);
            Arrays.fill(current, Double.NEGATIVE_INFINITY);
            for (int state = 0; state < states; state++) {
                int pitch = pitch(note, state);
                boolean original = pitch == note.pitch();
                if (state == PITCHES && pitch >= LOW && pitch <= HIGH) continue;
                double support = state == PITCHES ? 0 : observed.support[state];
                // Only backed alternatives may change a note, and direct strong evidence wins.
                if (!original && (support < .12 || certainty >= STRONG)) continue;
                double prior = state == PITCHES ? 0 : .11 * prior(local, localPeak, state)
                        + .055 * prior(song, songPeak, state);
                double emission = (support + (original ? .15 : 0))
                        * Math.clamp(Math.sqrt(duration(note) / .18), .65, 1.8)
                        + (prior + (pitch == repeat ? .12 : 0)) * (1 - certainty);
                for (int predecessor = 0; predecessor < states; predecessor++) {
                    double penalty = i == 0 ? 0 : transition(notes.get(i - 1), note,
                            pitch(notes.get(i - 1), predecessor), pitch, evidence[i - 1], observed);
                    double score = previous[predecessor] + emission - penalty;
                    if (score <= current[state]) continue;
                    current[state] = score;
                    back[i][state] = predecessor;
                }
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        int state = 0;
        for (int i = 1; i < states; i++)
            if (previous[i] > previous[state]) state = i;
        var result = new int[notes.size()];
        for (int i = notes.size() - 1; i >= 0; i--) {
            result[i] = pitch(notes.get(i), state);
            state = back[i][state];
        }
        return result;
    }

    /** Vote only across matching local rhythm and two independently corroborated neighbours. */
    private static int repeatPitch(List<Note> notes, Evidence[] evidence, int index) {
        if (index == 0 || index + 1 == notes.size()) return 0;
        var before = notes.get(index - 1);
        var after = notes.get(index + 1);
        if (confidence(evidence[index - 1], before.pitch()) < .55
                || confidence(evidence[index + 1], after.pitch()) < .55) return 0;
        double start = notes.get(index).start();
        double into = start - before.start(), out = after.start() - start;
        if (into > 1.5 || out > 1.5) return 0;
        int pitch = 0;
        for (int j = 1; j + 1 < notes.size(); j++) {
            var candidate = notes.get(j);
            if (Math.abs(candidate.start() - start) < 2
                    || confidence(evidence[j], candidate.pitch()) < STRONG) continue;
            var left = notes.get(j - 1);
            var right = notes.get(j + 1);
            if (left.pitch() != before.pitch() || right.pitch() != after.pitch()
                    || Math.abs(candidate.start() - left.start() - into) > .08
                    || Math.abs(right.start() - candidate.start() - out) > .08
                    || confidence(evidence[j - 1], left.pitch()) < .55
                    || confidence(evidence[j + 1], right.pitch()) < .55) continue;
            if (pitch != 0 && pitch != candidate.pitch()) return 0;
            pitch = candidate.pitch();
        }
        return pitch;
    }

    private static int pitch(Note note, int state) {
        return state == PITCHES ? note.pitch() : state + LOW;
    }

    private static double confidence(Evidence evidence, int pitch) {
        return pitch < LOW || pitch > HIGH ? 0 : evidence.confidence[pitch - LOW];
    }

    private static double prior(double[] distribution, double peak, int index) {
        double neighbour = Math.max(index == 0 ? 0 : distribution[index - 1],
                index + 1 == PITCHES ? 0 : distribution[index + 1]);
        return Math.max(0, Math.max(distribution[index], .55 * neighbour)) / Math.max(.25, peak);
    }

    private static double transition(Note before, Note after, int from, int to, Evidence previous, Evidence current) {
        double gap = after.start() - before.end();
        if (gap > PHRASE_GAP) return 0;
        int interval = Math.abs(to - from);
        double leap = .031 * Math.min(12, interval) + (interval >= 7 ? .12 : 0)
                + (interval > 0 && interval % 12 == 0 ? .14 : 0);
        double uncertainty = 1 - .85 * Math.min(confidence(previous, from), confidence(current, to));
        return leap * uncertainty * (1 - .65 * Math.max(0, gap) / PHRASE_GAP);
    }

    private static boolean hasSyllable(int[] syllables, double start, int tolerance) {
        int frame = (int) Math.round(start / HOP);
        int index = Arrays.binarySearch(syllables, frame - tolerance);
        if (index < 0) index = -index - 1;
        return index < syllables.length && syllables[index] <= frame + tolerance;
    }

    private static double duration(Note note) {
        return note.end() - note.start();
    }
}
