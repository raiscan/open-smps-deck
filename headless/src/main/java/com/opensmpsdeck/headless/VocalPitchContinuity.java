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
    private static final double PRIOR_WINDOW = 9;
    private static final double SECTION_GAP = 1.5;
    private static final int[] HARMONIC_INTERVALS = {12, 19, 24};
    private static final int[] ADJACENT_OCTAVES = {-12, 12};

    record Decision(String noteId, double sourceStart, double sourceEnd, int observedPitch, Integer selectedPitch,
                    double observedConfidence, double selectedConfidence, String reason, List<String> pitchEvidence) {}
    record Result(List<Note> notes, List<Decision> decisions) {}
    private record Evidence(double[] support, double[] confidence, double[] training,
                            double[] pitchClasses, double[] octave, boolean quiet) {}
    private record Path(int[] pitches, Cue[] cues) {}
    private record Cue(Register section, Register song, int repeat, int before, int after) {
        boolean neighbour(int pitch) { return pitch != 0 && (pitch == before || pitch == after); }
    }

    private record Register(double[] histogram, double total, double peak, int low, int high) {
        static Register of(double[] distribution) {
            double total = Arrays.stream(distribution).sum();
            double peak = Arrays.stream(distribution).max().orElse(0);
            double accumulated = 0;
            int low = 0, high = PITCHES - 1;
            for (int i = 0; i < PITCHES; i++) {
                accumulated += distribution[i];
                if (accumulated < .10 * total) low = i + 1;
                if (accumulated >= .90 * total) {
                    high = i;
                    break;
                }
            }
            return new Register(distribution.clone(), total, peak, low, high);
        }

        double preference(int pitch) {
            if (pitch < LOW || pitch > HIGH || total < .25) return 0;
            int index = pitch - LOW;
            double familiarity = Math.max(0, histogram[index]) / peak;
            double distance = Math.max(0, Math.max(low - index, index - high) - 1);
            return Math.min(1, total / 1.5) * (.5 * Math.sqrt(familiarity) - Math.min(1.5, distance / 5));
        }
    }

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
        var spectrum = track.vocalOctaveSpectrum();
        for (int i = 0; i < notes.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("vocal pitch selection cancelled");
            var note = notes.get(i);
            var measured = measure(note, track, periodic, model, floor, spectrum);
            evidence[i] = measured;
            addPrior(song, measured, 1);
        }
        var path = bestPath(notes, evidence, Register.of(song));
        var output = new ArrayList<Note>();
        var decisions = new ArrayList<Decision>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int pitch = path.pitches[i];
            var reasons = pitchReasons(note.pitch(), pitch, evidence[i], path.cues[i]);
            double observedConfidence = confidence(evidence[i], note.pitch());
            double selectedConfidence = confidence(evidence[i], pitch);
            boolean syllable = hasSyllable(syllables, note.start(), 2);
            var selected = pitch == note.pitch() ? note : new Note(note.id(), note.part(), note.start(),
                    note.end(), pitch, note.velocity(), note.source(), note.confidence());
            boolean articulated = syllable || track.clearAttack(selected);
            if (evidence[i].quiet && !articulated) {
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), null,
                        observedConfidence, selectedConfidence, "belowVocalFloor", reasons));
                continue;
            }
            if (!output.isEmpty() && !hasSyllable(syllables, note.start(), 5)) {
                var previous = output.getLast();
                double gap = note.start() - previous.end();
                if (previous.pitch() == pitch && gap <= .06
                        && (gap <= .011 || !articulated && track.bridges(previous, selected, List.of()))) {
                    output.set(output.size() - 1, previous.withTiming(previous.start(), Math.max(previous.end(), selected.end())));
                    decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), pitch,
                            observedConfidence, selectedConfidence, "sameSyllableFragment", reasons));
                    continue;
                }
            }
            if (duration(note) < .1 - 1e-8 && !(selectedConfidence >= .50 && articulated)) {
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), null,
                        observedConfidence, selectedConfidence, "shortWithoutArticulation", reasons));
                continue;
            }
            if (pitch != note.pitch())
                decisions.add(new Decision(note.id(), note.start(), note.end(), note.pitch(), pitch,
                        observedConfidence, selectedConfidence, reasons.getFirst(), reasons));
            output.add(selected);
        }
        return new Result(List.copyOf(output), List.copyOf(decisions));
    }

    private static Evidence measure(Note note, ReconstructionLeadAudio.Track track, int[] periodic,
                                    double[] model, double floor, VocalOctaveSpectrum octaveSpectrum) {
        var support = new double[PITCHES];
        var confidence = new double[PITCHES];
        var modelOctave = new double[PITCHES];
        var training = new double[PITCHES];
        var pitchClasses = new double[12];
        int from = Math.clamp((int) Math.ceil((note.start() + .01) / HOP), 0, model.length);
        int to = Math.clamp((int) Math.ceil((note.end() - .01) / HOP), from, model.length);
        double frames = Math.max(1, to - from);
        int audible = 0, voiced = 0;
        for (int frame = from; frame < to; frame++) {
            double level = track.frameLevel(frame);
            if (level >= .5 * floor) audible++;
            if (level < floor || model[frame] == 0 && periodic[frame] == 0) continue;
            if (model[frame] > 0) {
                int rounded = (int) Math.round(model[frame]);
                pitchClasses[Math.floorMod(rounded, 12)]++;
                voiced++;
            }
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
                double weight = (1 - .65 * harmonic) / frames;
                support[index] += (.42 * spectrum * independent + .34 * periodMatch * independent) * weight;
                modelOctave[index] += modelMatch * weight;
                double agreement = modelMatch * (.72 * periodMatch + .28 * spectrum * independent)
                        * (1 - harmonic);
                confidence[index] += agreement / frames;
                // Count only convergent frames in a stable stretch, and give each
                // frame to one pitch even when adjacent FFT bins both have energy.
                if (agreement >= .72 && periodic[frame] == pitch && Math.round(model[frame]) == pitch
                        && frame > 1 && frame + 2 < model.length
                        && Math.abs(model[frame - 2] - pitch) <= .7 && Math.abs(model[frame + 2] - pitch) <= .7)
                    training[index] += agreement * HOP;
            }
        }
        for (int i = 0; i < pitchClasses.length; i++) pitchClasses[i] /= Math.max(1, voiced);
        boolean quiet = audible < .15 * frames && to > from;
        double certainty = confidenceFor(confidence, note.pitch());
        double[] octave = quiet || certainty >= STRONG
                ? new double[PITCHES] : octaveSpectrum.measure(note.start(), note.end());
        // On ambiguous gates the tracker is more trustworthy about pitch class
        // than absolute register. Stem support and the path decide the octave.
        for (int i = 0; i < PITCHES; i++)
            support[i] += (.10 + .20 * certainty) * modelOctave[i];
        if (!quiet && certainty < STRONG) corroborateRegister(training, pitchClasses, octave, certainty);
        return new Evidence(support, confidence, training, pitchClasses, octave, quiet);
    }

    private static void corroborateRegister(double[] histogram, double[] pitchClasses, double[] octave,
                                            double certainty) {
        // Stable frames may agree on a subharmonic with RMVPE. Redistribute
        // part of their existing weight only when the octave contrast is clear.
        var corrected = histogram.clone();
        for (int index = 0; index < PITCHES; index++) {
            if (histogram[index] == 0 || pitchClasses[(index + LOW) % 12] < .60) continue;
            int best = index;
            for (int interval : ADJACENT_OCTAVES) {
                int candidate = index + interval;
                if (candidate >= 0 && candidate < PITCHES && octave[candidate] > octave[best]) best = candidate;
            }
            if (octave[best] - octave[index] < .65) continue;
            double weight = histogram[index] * (1 - certainty);
            corrected[index] -= weight;
            corrected[best] += weight;
        }
        System.arraycopy(corrected, 0, histogram, 0, PITCHES);
    }

    private static double match(int pitch, double observed) {
        return Math.clamp(1.5 - Math.abs(pitch - observed), 0, 1);
    }

    private static void addPrior(double[] distribution, Evidence evidence, int direction) {
        for (int i = 0; i < PITCHES; i++) distribution[i] += direction * evidence.training[i];
    }

    private static Path bestPath(List<Note> notes, Evidence[] evidence, Register song) {
        // Leave a state for a transcriber pitch outside the stem analyser's range.
        final int states = PITCHES + 1;
        var previous = new double[states];
        var current = new double[states];
        var back = new int[notes.size()][states];
        var cues = new Cue[notes.size()];
        var local = new double[PITCHES];
        int left = 0, right = 0, sectionEnd = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var observed = evidence[i];
            double certainty = confidence(observed, note.pitch());
            if (i == sectionEnd) {
                Arrays.fill(local, 0);
                left = right = i;
                sectionEnd = i + 1;
                while (sectionEnd < notes.size()
                        && notes.get(sectionEnd).start() - notes.get(sectionEnd - 1).end() <= SECTION_GAP)
                    sectionEnd++;
            }
            while (right < sectionEnd && notes.get(right).start() <= note.start() + PRIOR_WINDOW) {
                addPrior(local, evidence[right], 1);
                right++;
            }
            while (notes.get(left).start() < note.start() - PRIOR_WINDOW) {
                addPrior(local, evidence[left], -1);
                left++;
            }
            int repeat = certainty >= STRONG || observed.quiet ? 0 : repeatPitch(notes, evidence, i);
            boolean thinFragment = duration(note) <= .16 && certainty < .55
                    && observed.pitchClasses[Math.floorMod(note.pitch(), 12)] < .73;
            int before = thinFragment ? neighbourPitch(notes, evidence, i, -1) : 0;
            int after = thinFragment ? neighbourPitch(notes, evidence, i, 1) : 0;
            var cue = new Cue(Register.of(local), song, repeat, before, after);
            cues[i] = cue;
            Arrays.fill(current, Double.NEGATIVE_INFINITY);
            for (int state = 0; state < states; state++) {
                int pitch = pitch(note, state);
                boolean original = pitch == note.pitch();
                if (state == PITCHES && pitch >= LOW && pitch <= HIGH) continue;
                double support = state == PITCHES ? 0 : observed.support[state];
                boolean sameClass = (pitch - note.pitch()) % 12 == 0;
                double octave = state == PITCHES || !sameClass ? 0 : observed.octave[state];
                boolean anchor = cue.neighbour(pitch) && octave > -.5;
                if (!original && (certainty >= STRONG || observed.quiet
                        || support < .12 && !anchor && !(sameClass && (octave > .2 || pitch == repeat)))) continue;
                double prior = .28 * cue.section.preference(pitch) + .15 * song.preference(pitch);
                double repeatBonus = pitch == repeat && octave > -.5 ? .27 : 0;
                double attack = Math.clamp(Math.sqrt(duration(note) / .18), .65, 1.8);
                double classSupport = observed.pitchClasses[Math.floorMod(pitch, 12)];
                double localSupport = Math.max(support, anchor ? .15 : 0);
                double emission = (localSupport + .20 * classSupport + (original ? .035 + .13 * certainty : 0)) * attack
                        + (prior + repeatBonus) * (1 - certainty) + .44 * octave;
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
        return new Path(result, cues);
    }

    private static int neighbourPitch(List<Note> notes, Evidence[] evidence, int index, int direction) {
        int neighbour = index + direction;
        if (neighbour < 0 || neighbour >= notes.size()) return 0;
        var note = notes.get(index);
        var other = notes.get(neighbour);
        double gap = direction < 0 ? note.start() - other.end() : other.start() - note.end();
        return gap <= .085 && confidence(evidence[neighbour], other.pitch()) >= .36 ? other.pitch() : 0;
    }

    /** Match rhythmic pitch-class context on both sides, allowing an unmatched fragment. */
    private static int repeatPitch(List<Note> notes, Evidence[] evidence, int index) {
        var note = notes.get(index);
        int pitch = 0;
        for (int j = 0; j < notes.size(); j++) {
            var candidate = notes.get(j);
            if (Math.abs(candidate.start() - note.start()) < 2.5
                    || (candidate.pitch() - note.pitch()) % 12 != 0
                    || confidence(evidence[j], candidate.pitch()) < STRONG) continue;
            if (hasStrongContradiction(evidence[index], note.pitch(), candidate.pitch())) continue;
            int before = matchingContext(notes, evidence, index, j, -1);
            int after = matchingContext(notes, evidence, index, j, 1);
            if (before < 1 || after < 1 || before + after < 4) continue;
            if (pitch != 0 && pitch != candidate.pitch()) return 0;
            pitch = candidate.pitch();
        }
        return pitch;
    }

    private static int matchingContext(List<Note> notes, Evidence[] evidence, int target, int repeat, int direction) {
        int matches = 0;
        int next = repeat + direction;
        for (int i = target + direction; i >= 0 && i < notes.size(); i += direction) {
            double offset = direction * (notes.get(i).start() - notes.get(target).start());
            if (offset > 1.65) break;
            int pitch = notes.get(i).pitch();
            if (offset < .065 || !supportsPitchClass(evidence[i], pitch)) continue;
            while (next >= 0 && next < notes.size()) {
                double other = direction * (notes.get(next).start() - notes.get(repeat).start());
                if (other > offset + .16) break;
                int otherPitch = notes.get(next).pitch();
                boolean matchesAnchor = Math.abs(other - offset) <= .16
                        && (otherPitch - pitch) % 12 == 0 && supportsPitchClass(evidence[next], otherPitch);
                next += direction;
                if (matchesAnchor) {
                    matches++;
                    break;
                }
            }
        }
        return matches;
    }

    private static boolean supportsPitchClass(Evidence evidence, int pitch) {
        return !evidence.quiet && (confidence(evidence, pitch) >= .40
                || evidence.pitchClasses[Math.floorMod(pitch, 12)] >= .72);
    }

    private static boolean hasStrongContradiction(Evidence evidence, int original, int alternative) {
        return octave(evidence, original) - octave(evidence, alternative) >= .65
                || confidence(evidence, original) - confidence(evidence, alternative) >= .50;
    }

    private static List<String> pitchReasons(int original, int selected, Evidence evidence, Cue cue) {
        if (original == selected) return List.of();
        var reasons = new ArrayList<String>();
        if ((selected - original) % 12 == 0 && octave(evidence, selected) - octave(evidence, original) > .35)
            reasons.add("stemSpectrumOctave");
        if (selected == cue.repeat) reasons.add("repeatedPhraseOctave");
        if (cue.neighbour(selected)) reasons.add("weakFragmentContinuity");
        if (cue.section.preference(selected) - cue.section.preference(original) > .12)
            reasons.add("sectionRegisterAndPitchSet");
        if (cue.song.preference(selected) - cue.song.preference(original) > .12)
            reasons.add("songRegisterAndPitchSet");
        if (reasons.isEmpty()) reasons.add("pitchContinuity");
        return List.copyOf(reasons);
    }

    private static double octave(Evidence evidence, int pitch) {
        return confidenceFor(evidence.octave, pitch);
    }

    private static int pitch(Note note, int state) {
        return state == PITCHES ? note.pitch() : state + LOW;
    }

    private static double confidence(Evidence evidence, int pitch) {
        return confidenceFor(evidence.confidence, pitch);
    }

    private static double confidenceFor(double[] scores, int pitch) {
        return pitch < LOW || pitch > HIGH ? 0 : scores[pitch - LOW];
    }

    private static double transition(Note before, Note after, int from, int to, Evidence previous, Evidence current) {
        double gap = after.start() - before.end();
        if (gap > PHRASE_GAP) return 0;
        int interval = Math.abs(to - from);
        double leap = .031 * Math.min(12, interval) + (interval >= 7 ? .20 : 0)
                + (interval >= 10 ? .25 : 0);
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
