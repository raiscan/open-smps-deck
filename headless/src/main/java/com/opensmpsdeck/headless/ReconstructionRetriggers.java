package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionFastRuns.HOP;
import static com.opensmpsdeck.headless.ReconstructionFastRuns.Figure;
import static com.opensmpsdeck.headless.ReconstructionFastRuns.Step;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Finds repeated attacks in the partials of a transcribed held pitch. */
final class ReconstructionRetriggers {
    static final String REASON = "fast-retrigger";
    private static final double MIN_INTERVAL = .030;
    private static final double MAX_INTERVAL = .105;
    private static final int MIN_ATTACKS = 6;
    private static final int[] PARTIALS = {1, 2, 3, 4};

    private ReconstructionRetriggers() {}

    record Burst(String anchor, Note figure) {}

    static List<Burst> detect(ReferenceAudio audio, List<Note> notes) {
        var bursts = new ArrayList<Burst>();
        var ids = new java.util.HashSet<String>();
        for (var note : notes) ids.add(note.id());
        for (var note : notes) {
            if (note.run() != null || note.pitch() < 48 || note.pitch() > 92
                    || note.end() - note.start() < .24 || note.source().equals("external")) continue;
            // A quieter chord voice can share the lead's partials, especially its octave.
            if (notes.stream().anyMatch(other -> other.velocity() > note.velocity() + 4
                    && Math.abs(other.start() - note.start()) <= .030 && other.end() - other.start() >= .12)) continue;
            int index = 0;
            for (var figure : find(audio, note.pitch(), note.start(), note.end())) {
                if (competingAttacks(figure, note, notes)) continue;
                String id;
                do {
                    id = note.id() + "-retrigger-" + index++;
                } while (!ids.add(id));
                bursts.add(new Burst(note.id(), new Note(id, note.part(), figure.start(), figure.end(),
                        note.pitch(), note.velocity(), REASON, figure.confidence(), null, figure.steps())));
            }
        }
        return List.copyOf(bursts);
    }

    static boolean competingAttacks(Figure figure, Note held, List<Note> notes) {
        // A pad or octave can pulse with the lead. Require multiple aligned attacks at
        // one competing pitch before vetoing the split of a transcribed hold.
        var aligned = new java.util.HashMap<Integer, Integer>();
        for (var note : notes) {
            int interval = Math.abs(note.pitch() - held.pitch());
            if (interval == 0 || interval > 12 || note.end() <= figure.start() || note.start() >= figure.end()) continue;
            if (figure.steps().stream().anyMatch(step -> Math.abs(step.start() - note.start()) <= .020)
                    && aligned.merge(note.pitch(), 1, Integer::sum)
                    >= (interval == 12 ? 2 : Math.max(3, (figure.steps().size() + 1) / 2))) return true;
        }
        return false;
    }

    record Playback(List<ReconstructionAllocation.Entry> notes,
                    List<ReconstructionAllocation.RunPlacement> placements, java.util.Map<String, String> origins) {}

    static Playback articulate(List<ReconstructionAllocation.Entry> notes, List<Burst> bursts,
                               ReconstructionTimeMap timeline, ReconstructionVoicing.Articulation articulation) {
        if (bursts == null || bursts.isEmpty()) return new Playback(notes, List.of(), java.util.Map.of());
        var byAnchor = new HashMap<String, List<Note>>();
        for (var burst : bursts)
            byAnchor.computeIfAbsent(burst.anchor(), key -> new ArrayList<>()).add(timeline.projectNote(burst.figure()));
        var articulated = new ArrayList<ReconstructionAllocation.Entry>();
        var origins = new HashMap<String, String>();
        var placements = new ArrayList<ReconstructionAllocation.RunPlacement>();
        for (var entry : notes) {
            var figures = byAnchor.get(entry.note().id());
            if (!"FM1".equals(entry.lane()) || figures == null || entry.note().run() != null) {
                articulated.add(entry);
                continue;
            }
            var anchor = entry.note();
            int first = entry.startTick();
            int end = first + articulation.duration(entry.endTick() - first);
            var attacks = new java.util.TreeMap<Integer, String>();
            attacks.put(first, anchor.id());
            for (var figure : figures) {
                if (anchor.pitch() != figure.pitch() || anchor.part() != figure.part()) continue;
                int from = figure.startTick(timeline.frames()), to = figure.endTick(timeline.frames());
                // The selected lead must cover the whole burst. Honor shortened releases and lead splits.
                if (from < first || to > end) continue;
                var retained = new java.util.LinkedHashMap<Integer, Integer>();
                for (int i = 0; i < figure.run().size(); i++) {
                    var step = figure.run().get(i);
                    int tick = S3kReconstruction.toTicks(Math.round(step.start() * 1e6));
                    if (i == 0 && tick == first + 1) tick = first;
                    if (tick < to) retained.putIfAbsent(tick, i);
                }
                if (retained.size() < 2) continue;
                retained.forEach((tick, index) -> attacks.putIfAbsent(tick, figure.id() + "-step-" + index));
                var indexes = List.copyOf(retained.values());
                placements.add(new ReconstructionAllocation.RunPlacement(figure.id(), entry.lane(),
                        retained.firstEntry().getKey(), to, indexes,
                        indexes.size() == figure.run().size() ? REASON : REASON + "-subframe-merge"));
            }
            if (attacks.size() == 1) {
                articulated.add(entry);
                continue;
            }
            // Keep both outer gates. Do not insert a new attack at the end of a burst.
            for (var attack : attacks.entrySet()) {
                int start = attack.getKey();
                int stop = attacks.higherKey(start) == null ? end : attacks.higherKey(start);
                // The original logical onset selects the voice. The entry ticks supply the new attacks.
                var note = new Note(attack.getValue(), anchor.part(), anchor.start(), anchor.end(),
                        anchor.pitch(), anchor.velocity(), REASON, anchor.confidence());
                origins.put(note.id(), anchor.id());
                articulated.add(new ReconstructionAllocation.Entry(note, entry.role(), entry.lane(), start, stop,
                        entry.outcome(), entry.reason(), null));
            }
        }
        return new Playback(List.copyOf(articulated), List.copyOf(placements), java.util.Map.copyOf(origins));
    }

    static List<Figure> find(ReferenceAudio audio, int pitch, double start, double end) {
        var envelope = partialEnvelope(audio, pitch, start, end);
        if (envelope.length < MIN_ATTACKS * MIN_INTERVAL / HOP) return List.of();
        double[] flux = new double[envelope.length];
        int window = 3;
        for (int i = window; i + window < envelope.length; i++)
            flux[i] = envelope[i + window] - envelope[i - window];
        var attacks = new ArrayList<Integer>();
        for (int i = window; i + window < flux.length; i++) {
            if (flux[i] < .13 || flux[i] <= flux[i - 1] || flux[i] < flux[i + 1]) continue;
            int previous = attacks.isEmpty() ? -100 : attacks.getLast();
            if ((i - previous) * HOP < MIN_INTERVAL * .82) {
                if (flux[i] > flux[previous]) attacks.set(attacks.size() - 1, i);
            } else attacks.add(i);
        }
        var figures = new ArrayList<Figure>();
        for (int first = 0; first + MIN_ATTACKS <= attacks.size();) {
            int last = first + 1;
            int firstFrame = attacks.get(first);
            int interval = attacks.get(last) - firstFrame;
            double error = 0;
            while (interval * HOP >= MIN_INTERVAL && interval * HOP <= MAX_INTERVAL && last + 1 < attacks.size()) {
                int next = attacks.get(last + 1) - attacks.get(last);
                double jitter = Math.abs(next - interval) * HOP;
                if (next * HOP < MIN_INTERVAL || next * HOP > MAX_INTERVAL || jitter > Math.max(.009, interval * HOP * .20)) break;
                error += jitter;
                last++;
            }
            if (last - first + 1 < MIN_ATTACKS) {
                first++;
                continue;
            }
            // Flux locates an entrance, and the preceding span can include the held note's first attack.
            var steps = new ArrayList<Step>();
            double period = (attacks.get(last) - firstFrame) * HOP / (last - first);
            double previous = start + firstFrame * HOP;
            for (int i = first; i <= last; i++) {
                double next = i < last ? start + attacks.get(i + 1) * HOP : Math.min(end, previous + period);
                if (next > previous) steps.add(new Step(pitch, previous, next));
                previous = next;
            }
            double confidence = Math.clamp(.99 - error / ((last - first) * period), .78, .99);
            figures.add(new Figure(List.copyOf(steps), confidence, REASON));
            first = last + 1;
        }
        return List.copyOf(figures);
    }

    /** Positive flux of the combined, level-normalized partial series, sampled at detector hops. */
    static double[] partialEnvelope(ReferenceAudio audio, int pitch, double start, double end) {
        int rate = audio.sampleRate();
        int width = Math.max(8, (int) Math.round(.016 * rate));
        float[] samples = audio.samples();
        int frames = (int) Math.ceil(Math.max(0, Math.min(end, samples.length / (double) rate) - start) / HOP);
        if (frames < 1) return new double[0];
        var envelopes = new double[PARTIALS.length][frames];
        for (int h : PARTIALS) {
            double frequency = h * ReconstructionLeadAudio.hz(pitch);
            if (frequency >= rate / 2.0) break;
            double[] cos = new double[width], sin = new double[width];
            for (int i = 0; i < width; i++) {
                double window = .5 - .5 * Math.cos(Math.TAU * i / (width - 1));
                double angle = Math.TAU * frequency * i / rate;
                cos[i] = window * Math.cos(angle);
                sin[i] = window * Math.sin(angle);
            }
            for (int frame = 0; frame < frames; frame++) {
                int first = (int) Math.round((start + frame * HOP) * rate) - width / 2;
                double re = 0, im = 0;
                for (int i = Math.max(0, -first), last = Math.min(width, samples.length - first); i < last; i++) {
                    re += samples[i + first] * cos[i];
                    im += samples[i + first] * sin[i];
                }
                envelopes[h - 1][frame] = Math.hypot(re, im) * 4 / width;
            }
        }
        var levels = Arrays.stream(envelopes).mapToDouble(band -> {
            var sorted = band.clone();
            Arrays.sort(sorted);
            return sorted[(int) (.90 * (sorted.length - 1))];
        }).toArray();
        double max = Arrays.stream(levels).max().orElse(0);
        var sum = new double[frames];
        double total = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] < Math.max(.002, max * .12)) continue;
            double weight = Math.sqrt(levels[i]);
            total += weight;
            for (int j = 0; j < frames; j++) sum[j] += weight * Math.min(1.5, envelopes[i][j] / levels[i]);
        }
        if (total > 0) for (int i = 0; i < sum.length; i++) sum[i] /= total;
        return sum;
    }
}
