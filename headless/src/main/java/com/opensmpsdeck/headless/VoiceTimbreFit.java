package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionVoicing.FmTimbre;
import static com.opensmpsdeck.headless.VoicePatchShortlist.RankedPatch;
import static com.opensmpsdeck.headless.VoiceSpectralBalance.Profile;

/** Rechecks stem-backed patches at full bandwidth after the envelope screens. */
final class VoiceTimbreFit {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final int PER_TICK = RATE / S3kRenderer.PRESENTATIONS_PER_SECOND;
    private static final double WINDOW = VoiceSpectralBalance.SIZE / (double) RATE;
    private static final int PROBE_LIMIT = 12;
    private static final double MIN_LEVEL_DB = -55;
    private static final double MIN_EXCESS_IMPROVEMENT_DB = .5;
    private static final double COMPARABLE_LOSS_DB = .5;

    record Choice(RankedPatch patch, FmTimbre timbre, double excess, double distance) {}
    private record Probe(int pitch, double offset, Profile source, boolean held) {}
    private record Exposed(ReconstructionAllocation.Entry entry, int nextStart) {}

    interface Verify {
        boolean accepts(Choice choice) throws IOException;
    }

    private final InstrumentCatalogue catalogue;
    private final VoiceSpectralBalance analyzer = new VoiceSpectralBalance();
    private final List<Probe> probes;
    private final HashMap<Integer, Integer> probeTicks = new HashMap<>();

    VoiceTimbreFit(InstrumentCatalogue catalogue, Path stem, List<ReconstructionAllocation.Entry> notes,
                  VoiceWindows windows) throws IOException {
        this.catalogue = catalogue;
        double end = ReferenceAudio.durationMicros(stem) / 1e6;
        var ordered = notes.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList();
        var exposed = new ArrayList<Exposed>();
        var range = TargetProfile.of(TargetGame.S3K).fm();
        int precedingEnd = 0;
        for (int i = 0; i < ordered.size(); i++) {
            var entry = ordered.get(i);
            var note = entry.note();
            int start = entry.startTick(), stop = entry.endTick();
            int next = i + 1 < ordered.size() ? ordered.get(i + 1).startTick() : Integer.MAX_VALUE;
            if (note.run() == null && entry.lane().startsWith("FM") && precedingEnd <= start + 1
                    && next >= start + 3 && stop >= start + 3 && windows.trainingAttack(start, 3)
                    && note.pitch() >= range.low() && note.pitch() <= range.high())
                exposed.add(new Exposed(entry, next));
            precedingEnd = Math.max(precedingEnd, stop);
        }
        // Take attacks across the part and across registers. Probe each hold
        // while it is still isolated, even if it is shorter than the sustain screen.
        probes = new ArrayList<>();
        int count = Math.min(PROBE_LIMIT, exposed.size());
        for (int i = 0; i < count; i++) {
            var item = exposed.get((2 * i + 1) * exposed.size() / (2 * count));
            var entry = item.entry();
            var note = entry.note();
            double sourceStart = note.start();
            double playable = Math.min((entry.endTick() - entry.startTick()) / 60.0,
                    entry.note().end() - entry.note().start());
            if (sourceStart + WINDOW >= end || playable < WINDOW) continue;
            double following = (item.nextStart() - entry.startTick()) / 60.0;
            probe(stem, note.pitch(), sourceStart, 0, false);
            double held = Math.min(.32, .72 * Math.min(playable, following) - WINDOW);
            if (held >= .075 && sourceStart + held + WINDOW < end)
                probe(stem, note.pitch(), sourceStart, held, true);
        }
    }

    private void probe(Path stem, int pitch, double start, double offset, boolean held) throws IOException {
        var audio = ReferenceAudio.read(stem, start + offset, WINDOW);
        if (audio.sampleRate() < 12000) return;
        var source = analyzer.measure(audio.samples(), 0, audio.sampleRate(), EvidenceSpectrum.hz(pitch));
        if (source.levelDb() >= MIN_LEVEL_DB) {
            probes.add(new Probe(pitch, offset, source, held));
            int ticks = (int) Math.ceil((offset + WINDOW) * S3kRenderer.PRESENTATIONS_PER_SECOND);
            probeTicks.merge(pitch, ticks, Math::max);
        }
    }

    Choice refine(RankedPatch original, List<RankedPatch> allowed, Verify verify) throws IOException {
        if (probes.stream().filter(probe -> !probe.held()).count() < 3) return null;
        var baseline = assess(original, null);
        if (baseline.excess() <= MIN_EXCESS_IMPROVEMENT_DB) return null;

        // Keep the patch's operator ratios and envelope when register edits
        // alone can bring it back within the upper-band tolerance.
        var adjustments = new ArrayList<Choice>();
        optimize(baseline, adjustments);
        var local = bestImprovement(adjustments, baseline, MIN_EXCESS_IMPROVEMENT_DB, verify);
        if (local != null) return preferSmallerFeedback(local, adjustments, baseline, verify);

        var ranked = new ArrayList<Choice>();
        for (var patch : allowed) if (!patch.id().equals(original.id())) ranked.add(assess(patch, null));
        ranked.sort(Comparator.comparingDouble(VoiceTimbreFit::loss));
        var trials = new ArrayList<>(ranked);
        trials.addAll(adjustments);
        for (var seed : ranked.stream().limit(3).toList()) optimize(seed, trials);
        return bestImprovement(trials, baseline, Double.POSITIVE_INFINITY, verify);
    }

    private Choice preferSmallerFeedback(Choice best, List<Choice> adjustments, Choice baseline, Verify verify)
            throws IOException {
        if (!feedbackOnly(best)) return best;
        var asset = catalogue.match(best.patch().id(), TargetGame.S3K).asset();
        int feedback = (asset.bytes()[0] >> 3) & 7;
        // Rounded feedback steps can make adjacent candidates equivalent
        // on the probes. Preserve more of the patch in those near ties.
        var comparable = adjustments.stream().filter(VoiceTimbreFit::feedbackOnly)
                .filter(choice -> choice.excess() <= MIN_EXCESS_IMPROVEMENT_DB
                        && choice.excess() + MIN_EXCESS_IMPROVEMENT_DB < baseline.excess()
                        && loss(choice) + 1 < loss(baseline)
                        && loss(choice) <= loss(best) + COMPARABLE_LOSS_DB)
                .sorted(Comparator.comparingInt(choice -> Math.abs(feedback - choice.timbre().feedback())))
                .toList();
        for (var choice : comparable) {
            if (choice.equals(best) || verify.accepts(choice)) return choice;
        }
        return best;
    }

    private static boolean feedbackOnly(Choice choice) {
        return choice.timbre() != null && choice.timbre().modulatorOffsets().stream().allMatch(offset -> offset == 0);
    }

    private static Choice bestImprovement(List<Choice> trials, Choice baseline, double maxExcess, Verify verify)
            throws IOException {
        trials.sort(Comparator.comparingDouble(VoiceTimbreFit::loss));
        for (var choice : trials) {
            if (choice.excess() > maxExcess || choice.excess() + MIN_EXCESS_IMPROVEMENT_DB >= baseline.excess()
                    || loss(choice) + 1 >= loss(baseline)) continue;
            if (verify.accepts(choice)) return choice;
        }
        return null;
    }

    private static double loss(Choice choice) {
        return choice.distance() + 3 * choice.excess();
    }

    private void optimize(Choice seed, List<Choice> trials) throws IOException {
        var asset = catalogue.match(seed.patch().id(), TargetGame.S3K).asset();
        var bytes = asset.bytes();
        int feedback = (bytes[0] >> 3) & 7;
        var current = seed;
        // First sample the whole small feedback range, then step each
        // modulator independently. Repeat feedback after TLs have moved.
        current = feedback(current, asset.id(), feedback, trials);
        for (int step : new int[]{8, 3}) {
            for (int slot = 0; slot < 4; slot++) {
                if (!FmTimbre.modulator(bytes, slot)) continue;
                var start = offsets(current);
                int raw = bytes[21 + slot] & 127;
                int selectedFeedback = current.timbre() == null ? feedback : current.timbre().feedback();
                for (int delta : new int[]{-step, step, 2 * step, 3 * step, 4 * step}) {
                    var next = new ArrayList<>(start);
                    int offset = Math.clamp(start.get(slot) + delta, -raw, 127 - raw);
                    if (offset == start.get(slot)) continue;
                    next.set(slot, offset);
                    current = tryTimbre(current, new FmTimbre(asset.id(), List.copyOf(next), selectedFeedback), trials);
                }
            }
        }
        feedback(current, asset.id(), feedback, trials);
    }

    private Choice feedback(Choice current, String asset, int original, List<Choice> trials) throws IOException {
        var offsets = offsets(current);
        int selected = current.timbre() == null ? original : current.timbre().feedback();
        for (int feedback = 0; feedback <= 7; feedback++) if (feedback != selected) {
            var trial = assess(current.patch(), new FmTimbre(asset, offsets, feedback));
            if (feedbackOnly(trial) || loss(trial) < loss(current)) trials.add(trial);
            if (loss(trial) < loss(current)) current = trial;
        }
        return current;
    }

    private static List<Integer> offsets(Choice choice) {
        return choice.timbre() == null ? List.of(0, 0, 0, 0) : choice.timbre().modulatorOffsets();
    }

    private Choice tryTimbre(Choice current, FmTimbre timbre, List<Choice> trials) throws IOException {
        var trial = assess(current.patch(), timbre);
        if (loss(trial) < loss(current)) {
            trials.add(trial);
            return trial;
        }
        return current;
    }

    private Choice assess(RankedPatch patch, FmTimbre timbre) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FM timbre fit cancelled");
        var renders = new HashMap<Integer, float[]>();
        double[] excess = new double[2], distance = new double[2], weights = new double[2];
        for (var probe : probes) {
            var samples = renders.get(probe.pitch());
            if (samples == null) {
                int ticks = probeTicks.get(probe.pitch());
                var project = CataloguePlayback.fmTimbreProbe(catalogue, patch.id(), probe.pitch(), ticks, TargetGame.S3K, timbre);
                samples = render(project, ticks);
                renders.put(probe.pitch(), samples);
            }
            int partition = probe.held() ? 1 : 0;
            var candidate = analyzer.measure(samples, (int) Math.round(probe.offset() * RATE), RATE,
                    EvidenceSpectrum.hz(probe.pitch()));
            double weight = Math.pow(10, probe.source().levelDb() / 20);
            excess[partition] += weight * probe.source().excess(candidate);
            distance[partition] += weight * (probe.source().distance(candidate)
                    + Math.max(0, MIN_LEVEL_DB - candidate.levelDb()));
            weights[partition] += weight;
        }
        return new Choice(patch, timbre, mean(excess, weights), mean(distance, weights));
    }

    private static double mean(double[] value, double[] weight) {
        double attacks = value[0] / weight[0];
        return weight[1] == 0 ? attacks : (attacks + value[1] / weight[1]) / 2;
    }

    private static float[] render(StudioProject project, int ticks) throws IOException {
        var samples = new float[ticks * PER_TICK];
        int[] at = {0};
        TargetPlayback.prepare(project).render(ticks, pcm -> {
            for (int i = 0; i < pcm.length; i += 2) samples[at[0]++] = (pcm[i] + pcm[i + 1]) / 65536f;
        });
        return samples;
    }
}
