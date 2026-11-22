package com.opensmpsdeck.headless;


import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionVoicing.*;
import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;
import static com.opensmpsdeck.headless.VoiceSpectra.SAMPLES_PER_TICK;

/** Check low-register candidates on phrase excerpts with the actual note gates and preceding notes. */
final class LowRegisterFit {
    private static final int MAX_PROBES = 16;
    private static final int HISTORY = 12;
    private static final int SEPARATION = 8;
    private static final int MIN_SPAN = 5;
    private static final int MAX_SPAN = 7;
    private static final double MARGIN = .02;

    private record Probe(int start, int length, int pitch, LowRegisterSpectrum.Profile target) {}
    private record Cost(double fit, double check, double centroidError) {
        boolean improves(Cost other) {
            return fit < other.fit * (1 - MARGIN) && check < other.check * (1 - MARGIN)
                    && centroidError <= other.centroidError;
        }
    }

    record Selection(VoicePatchShortlist.RankedPatch patch, Articulation articulation, boolean changedArticulation,
                     double fit, double check) {}

    private final LowRegisterSpectrum spectra = new LowRegisterSpectrum();
    private final List<Probe> probes = new ArrayList<>();
    private final List<ReconstructionAllocation.Entry> packed = new ArrayList<>();
    private int frames;

    private LowRegisterFit(Path stem, List<ReconstructionAllocation.Entry> notes,
                           List<ReconstructionAllocation.Entry> isolated) throws IOException {
        double duration = ReferenceAudio.durationMicros(stem) / 1e6;
        var eligible = isolated.stream()
                .filter(n -> n.note().pitch() <= LowRegisterSpectrum.MAX_PITCH && span(n) >= MIN_SPAN
                        && (n.startTick() + 1 + span(n)) / (double) PRESENTATIONS_PER_SECOND <= duration)
                .toList();
        int count = Math.min(eligible.size(), MAX_PROBES);
        for (int i = 0; i < count; i++) {
            int from = i * eligible.size() / count, to = (i + 1) * eligible.size() / count;
            for (int at = from; at < to; at++) {
                var note = eligible.get(at);
                int start = note.startTick() + 1, length = span(note);
                int pitch = note.note().pitch();
                var audio = ReferenceAudio.read(stem, start / (double) PRESENTATIONS_PER_SECOND,
                        length / (double) PRESENTATIONS_PER_SECOND);
                var target = spectra.profile(VoiceSpectra.resample(audio, length * SAMPLES_PER_TICK), pitch);
                if (target == null || target.harmonics()[0] < -13) continue;
                int first = Math.max(0, note.startTick() - HISTORY);
                int shift = frames - first;
                int end = start + length;
                for (var entry : notes) {
                    if (entry.startTick() >= end) break;
                    if (entry.endTick() <= first) continue;
                    packed.add(new ReconstructionAllocation.Entry(entry.note(), entry.role(), entry.lane(),
                            Math.max(first, entry.startTick()) + shift, Math.min(end, entry.endTick()) + shift,
                            ReconstructionAllocation.Outcome.kept, null, null));
                }
                probes.add(new Probe((start + shift) * SAMPLES_PER_TICK, length * SAMPLES_PER_TICK, pitch, target));
                frames += end - first + SEPARATION;
                break;
            }
        }
    }

    static Selection refine(S3kReconstruction.Score source, InstrumentCatalogue catalogue, Path stem, String id, Lane current,
                            List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> isolated,
                            List<VoicePatchShortlist.RankedPatch> candidates) throws IOException {
        if (candidates.isEmpty()) return null;
        var probe = new LowRegisterFit(stem, notes, isolated);
        if (probe.probes.size() < 4) return null;
        var baseline = probe.evaluate(source, catalogue, id, current, current.variantId(), current.articulation());
        var best = baseline;
        Selection next = null;
        for (var patch : candidates) {
            var art = current.articulation();
            var alternatives = art.minimumReleaseRate() >= 9 ? List.of(art)
                    : List.of(art, new Articulation("fast-release", art.gate(), 9));
            for (var articulation : alternatives) {
                if (patch.id().equals(current.variantId()) && articulation.equals(art)) continue;
                var cost = probe.evaluate(source, catalogue, id, current, patch.id(), articulation);
                if (cost.fit() + cost.check() < best.fit() + best.check() && cost.improves(baseline)) {
                    best = cost;
                    next = new Selection(patch, articulation, !articulation.equals(art), cost.fit(), cost.check());
                }
            }
        }
        return next;
    }

    private Cost evaluate(S3kReconstruction.Score source, InstrumentCatalogue catalogue, String id,
                          Lane original, String variant, Articulation articulation) throws IOException {
        var settings = new Lane(original.role(), variant, List.of(), original.gain(), articulation, original.effects(),
                DEFAULT, DEFAULT, DEFAULT, original.instruments());
        var choices = ReconstructionVoiceStage.choices(catalogue, settings, packed);
        var allocation = new ReconstructionAllocation.Result(ReconstructionStages.VERSION, packed);
        var project = S3kReconstruction.arrangeAllocated("Low register probe", 30, frames, source, allocation,
                null, catalogue, Map.of(id, choices), Map.of(), Map.of(id, settings), false).project();
        var mono = VoiceWindows.renderMono(project, frames);
        double fit = 0, check = 0, centroidError = 0;
        for (int i = 0; i < probes.size(); i++) {
            var probe = probes.get(i);
            var rendered = spectra.profile(mono, probe.start(), probe.length(), probe.pitch());
            double error = LowRegisterSpectrum.distance(probe.target(), rendered);
            if (i % 2 == 0) fit += error;
            else check += error;
            centroidError += rendered == null ? 4 : Math.abs(Math.log(rendered.centroid() / probe.target().centroid()));
        }
        return new Cost(fit, check, centroidError);
    }

    private static int span(ReconstructionAllocation.Entry note) {
        return Math.min(MAX_SPAN, note.endTick() - note.startTick() - 1);
    }
}
