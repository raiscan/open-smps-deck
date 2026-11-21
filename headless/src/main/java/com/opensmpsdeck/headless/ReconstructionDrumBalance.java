package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.CompiledProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionLevels.referenceLevel;
import static com.opensmpsdeck.headless.ReconstructionLevels.renderLevel;
import static com.opensmpsdeck.headless.ReconstructionLevels.samples;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role.drums;

/** Balance the drum kit as a group after patch, articulation and part levels have been fitted. */
final class ReconstructionDrumBalance {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final double BALANCE_TOLERANCE_LU = 1;
    private static final double QUANTIZATION_TOLERANCE_LU = .25;
    private static final double PEAK_LIMIT = Math.pow(10, -1.0 / 20);

    private ReconstructionDrumBalance() {}

    static ReconstructionVoicing.Result fit(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
            ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result voicing) throws IOException {
        var stems = score.inputs().options(score.durationUs()).stems();
        if (stems.isEmpty()) return voicing;
        var matches = ReconstructionCli.voicedMatches(catalogue, allocation, voicing).byLane();
        var drumNotes = new ArrayList<ReconstructionAllocation.Entry>();
        var toneNotes = new ArrayList<ReconstructionAllocation.Entry>();
        var drumStems = new LinkedHashSet<Path>();
        var toneStems = new LinkedHashSet<Path>();
        Path mix = Path.of(score.inputs().wav());
        for (var entry : allocation.notes()) {
            if (entry.lane() == null || !playable(entry, matches)) continue;
            Path stem = stems.get(entry.note().part());
            // An unreferenced audible part makes the relative target ambiguous.
            if (stem == null || stem.equals(mix)) return voicing;
            if (entry.role() == drums) {
                drumNotes.add(entry);
                drumStems.add(stem);
            } else {
                toneNotes.add(entry);
                toneStems.add(stem);
            }
        }
        if (drumStems.size() != 1 || toneStems.isEmpty() || toneStems.contains(drumStems.getFirst())) return voicing;
        int samples = (int) Math.round(score.durationUs() * (double) RATE / 1_000_000);
        for (var stem : toneStems) samples = Math.min(samples, samples(stem));
        samples = Math.min(samples, samples(drumStems.getFirst()));
        // Avoid calibrating the whole arrangement against a partial stem export.
        if (samples < .95 * score.durationUs() * RATE / 1_000_000) return voicing;
        double referenceDrums = referenceLevel(drumStems, List.of(), samples).power();
        double referenceTone = referenceLevel(toneStems, List.of(), samples).power();
        if (referenceDrums == 0 || referenceTone == 0) return voicing;

        var timeline = ReconstructionTimeMap.of(score);
        int frames = Math.min(S3kRenderer.MAX_PRESENTATIONS,
                (int) Math.ceil(timeline.project(samples / (double) RATE) * 60));
        int chipSamples = frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        var probe = new Probe(score, catalogue, allocation, timeline, matches);
        var nativeTone = renderLevel(probe.compile(toneNotes, voicing), chipSamples);
        var nativeDrums = renderLevel(probe.compile(drumNotes, voicing), chipSamples);
        if (nativeDrums.power() == 0 || nativeTone.power() == 0) return voicing;
        double target = nativeTone.power() * (referenceDrums / referenceTone);
        double correction = db(target / nativeDrums.power());
        if (Math.abs(correction) <= BALANCE_TOLERANCE_LU) return voicing;

        // PSG volume steps are 2 dB each, and saved drum gains are bounded by 4×.
        int noiseStep = Math.clamp((int) Math.round(correction / 2), -15, 6);
        double dacGain = Math.clamp(amplitude(correction), .125, 4);
        var fitted = apply(voicing, drumNotes, dacGain, noiseStep);
        var rendered = renderLevel(probe.compile(drumNotes, fitted), chipSamples);
        // Re-measure after native quantization. DAC can absorb the residual
        // from rounding the PSG level or from rounding/clipping PCM bytes.
        if (rendered.power() > 0 && Math.abs(db(target / rendered.power())) > QUANTIZATION_TOLERANCE_LU
                && voicing.lanes().containsKey("DAC")) {
            double refinedGain = Math.clamp(dacGain * Math.sqrt(target / rendered.power()), .125, 4);
            var refined = apply(voicing, drumNotes, refinedGain, noiseStep);
            var check = renderLevel(probe.compile(drumNotes, refined), chipSamples);
            if (check.power() > 0 && Math.abs(db(target / check.power())) < Math.abs(db(target / rendered.power()))) {
                fitted = refined;
                rendered = check;
            }
        }
        if (rendered.power() == 0 || Math.abs(db(target / rendered.power())) >= Math.abs(correction)) return voicing;
        if (correction > 0) {
            var all = new ArrayList<>(toneNotes);
            all.addAll(drumNotes);
            var peak = renderLevel(probe.compile(all, fitted), chipSamples).peak();
            if (peak > PEAK_LIMIT && peak > renderLevel(probe.compile(all, voicing), chipSamples).peak()) return voicing;
        }
        return fitted;
    }

    private static boolean playable(ReconstructionAllocation.Entry entry,
            Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches) {
        if (entry.role() == drums && entry.lane().equals("PSG3")) return true;
        var key = new ReconstructionVoices.Key(entry.note().part(), entry.lane().equals("DAC") ? entry.note().pitch() : null);
        var choice = matches.get(entry.lane()).get(key);
        return choice != null && choice.assetId() != null;
    }

    private static ReconstructionVoicing.Result apply(ReconstructionVoicing.Result base,
            List<ReconstructionAllocation.Entry> notes, double dacGain, int noiseStep) {
        var byLane = new LinkedHashMap<String, Map<Integer, Double>>();
        for (var entry : notes) {
            String id = entry.lane();
            var lane = base.lanes().get(id);
            var gains = byLane.computeIfAbsent(id, ignored ->
                    lane.drumGains() == null ? new TreeMap<>() : new TreeMap<>(lane.drumGains()));
            int pitch = entry.note().pitch();
            double original = lane.drumGain(pitch);
            if (id.equals("DAC")) {
                gains.put(pitch, Math.min(4, original * dacGain));
            } else if (original > 0) {
                int step = (int) Math.round(10 * Math.log10(original));
                gains.put(pitch, amplitude(2 * Math.clamp(step + noiseStep, -15, 6)));
            }
        }
        var lanes = new LinkedHashMap<>(base.lanes());
        byLane.forEach((id, gains) -> lanes.put(id, lanes.get(id).withDrumGains(gains)));
        return new ReconstructionVoicing.Result(base.version(), lanes, base.fitWindows(), base.sustainWarning(),
                base.voiceAssignments(), base.sourceInstruments(), base.lanePolicies());
    }

    private record Probe(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
            ReconstructionAllocation.Result allocation, ReconstructionTimeMap timeline,
            Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches) {
        CompiledProject compile(List<ReconstructionAllocation.Entry> notes, ReconstructionVoicing.Result voicing)
                throws IOException {
            var ordered = notes.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList();
            var playback = ReconstructionRetriggers.articulate(ordered, score.retriggers(), timeline, voicing::articulation);
            var group = new ReconstructionAllocation.Result(allocation.version(), playback.notes(), allocation.timeBase(),
                    null, null, null, null, allocation.chordVoicings());
            var source = new S3kReconstruction.Score(score.source().parts(), List.of(), List.of());
            var arrangement = S3kReconstruction.arrangeAllocated("Level probe", score.inputs().ticksPerBeat(),
                    timeline.frames(), source, group, null, catalogue, matches, Map.of(), voicing.lanes(),
                    true, false, Map.of(), playback.origins(), Map.of(), null, Map.of(), voicing.sourceInstruments());
            return ProjectCompiler.compileWithAssets(arrangement.project());
        }
    }

    private static double db(double power) {
        return 10 * Math.log10(power);
    }

    private static double amplitude(double db) {
        return Math.pow(10, db / 20);
    }
}
