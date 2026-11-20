package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Evidence that a pitched layer follows the attacks of an accepted figure. */
final class ReconstructionFigureVoices {
    private static final int MAX_INTERVAL = 12;
    private static final double FRAME = 1.0 / S3kRenderer.PRESENTATIONS_PER_SECOND;

    record Voice(String role, int interval, List<Integer> pitches, double confidence) {}
    private record Candidate(int interval, double confidence) {}

    private ReconstructionFigureVoices() {}

    static List<Voice> find(ReferenceAudio audio, Note figure, List<Note> partNotes) {
        boolean repeated = figure.source().equals(ReconstructionRetriggers.REASON);
        var candidates = new ArrayList<Candidate>();
        var spectra = figure.run().stream().map(step -> peaks(audio, (step.start() + step.end()) / 2,
                Math.min(.040, .88 * step.duration()))).toList();
        for (int interval = -MAX_INTERVAL; interval <= MAX_INTERVAL; interval++) {
            // An octave doubling cannot be established solely from the octave harmonic.
            if (interval == 0 || Math.abs(interval) == 12) continue;
            int pitch = figure.pitch() + interval;
            if (pitch < 48 || pitch > 96) continue;
            int supported = 0;
            for (int i = 0; i < spectra.size(); i++) {
                int anchor = figure.run().get(i).pitch();
                int candidate = anchor + interval;
                if (candidate < 12 || candidate > 106) {
                    supported = 0;
                    break;
                }
                double level = spectra.get(i).getOrDefault(candidate, 0.0);
                if (level < Math.max(.004, .23 * spectra.get(i).getOrDefault(anchor, 0.0))) continue;
                if (!repeated && ((i > 0 && spectra.get(i - 1).getOrDefault(candidate, 0.0) > level * .65)
                        || (i + 1 < spectra.size() && spectra.get(i + 1).getOrDefault(candidate, 0.0) > level * .65)))
                    continue;
                supported++;
            }
            double support = supported / (double) spectra.size();
            if (support < .80) continue;
            if (repeated && (Math.abs(interval) < 3 || !repeatMatches(audio, figure, partNotes, pitch))) continue;
            candidates.add(new Candidate(interval, support));
        }
        if (candidates.isEmpty()) return List.of();
        candidates.add(new Candidate(0, figure.confidence()));
        candidates.sort(Comparator.comparingInt(Candidate::interval).reversed());
        return candidates.stream().map(candidate -> new Voice(candidate == candidates.getFirst() ? "lead" : "harmony",
                candidate.interval, figure.run().stream().map(step -> step.pitch() + candidate.interval).toList(),
                candidate.confidence)).toList();
    }

    private static boolean repeatMatches(ReferenceAudio audio, Note figure, List<Note> notes, int pitch) {
        // Require an independent pitch anchor in the transcription, then measure its own onsets.
        if (notes.stream().noneMatch(note -> note.pitch() == pitch && note.start() <= figure.start() + FRAME
                && note.end() >= figure.end() - FRAME)) return false;
        double start = Math.max(0, figure.start() - FRAME);
        double end = figure.end() + FRAME;
        for (var track : ReconstructionRetriggers.find(audio, pitch, start, end)) {
            long aligned = figure.run().stream().filter(step -> track.steps().stream()
                    .anyMatch(other -> Math.abs(other.start() - step.start()) <= FRAME)).count();
            if (aligned >= Math.ceil(figure.run().size() * .80)) return true;
        }
        return false;
    }

    private static Map<Integer, Double> peaks(ReferenceAudio audio, double center, double seconds) {
        int rate = audio.sampleRate();
        int width = (int) Math.round(seconds * rate);
        int start = (int) Math.round(center * rate) - width / 2;
        if (width < 8 || start < 0 || start + width > audio.samples().length) return Map.of();
        double[] window = new double[width];
        for (int i = 0; i < width; i++) window[i] = .5 - .5 * Math.cos(Math.TAU * i / (width - 1));
        var spectrum = new LeadEffectMatcher.Spectrum();
        spectrum.transform(audio.samples(), start, window);
        var result = new HashMap<Integer, Double>();
        double binHz = rate / (double) spectrum.real.length;
        int from = (int) Math.floor(ReconstructionLeadAudio.hz(44) / binHz);
        int to = Math.min(spectrum.real.length / 2 - 1, (int) Math.ceil(ReconstructionLeadAudio.hz(106) / binHz));
        for (int i = Math.max(1, from); i <= to; i++) {
            double a = spectrum.power(i - 1), b = spectrum.power(i), c = spectrum.power(i + 1);
            if (b <= a || b <= c || b < 1e-10) continue;
            a = Math.log(Math.max(1e-20, a));
            c = Math.log(Math.max(1e-20, c));
            double hz = (i + .5 * (a - c) / (a + c - 2 * Math.log(b))) * binHz;
            double pitch = 69 + 12 / Math.log(2) * Math.log(hz / 440);
            int key = (int) Math.round(pitch);
            if (Math.abs(pitch - key) <= .32) result.merge(key, 4 * Math.sqrt(b) / width, Math::max);
        }
        return result;
    }
}
