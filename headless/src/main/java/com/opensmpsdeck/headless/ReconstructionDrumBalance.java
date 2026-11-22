package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
        try {
            return fitBalance(score, catalogue, allocation, voicing);
        } catch (IllegalArgumentException failure) {
            if (score.inputs().audioOnly() == null || !ReconstructionCli.bankOverflow(failure)) throw failure;
            // The optional level probe uses the full allocation. Assembly can
            // still fit it into the native bank after reducing backing parts.
            return voicing;
        }
    }

    private static ReconstructionVoicing.Result fitBalance(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
            ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result voicing) throws IOException {
        var stems = score.inputs().options(score.durationUs()).stems();
        if (stems.isEmpty()) return voicing;
        var probe = new ReconstructionLevels.Probe(score, catalogue, allocation, voicing);
        var drumNotes = new ArrayList<ReconstructionAllocation.Entry>();
        var toneNotes = new ArrayList<ReconstructionAllocation.Entry>();
        var drumStems = new LinkedHashSet<Path>();
        var toneStems = new LinkedHashSet<Path>();
        Path mix = Path.of(score.inputs().wav());
        for (var entry : probe.notes()) {
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
        var referenceKit = referenceLevel(drumStems, List.of(), samples).meter();
        var active = referenceKit.activeBlocks();
        double referenceDrums = referenceKit.powerOn(active);
        double referenceTone = referenceLevel(toneStems, List.of(), samples).meter().powerOn(active);
        if (referenceDrums == 0 || referenceTone == 0) return voicing;

        var timeline = probe.timeline();
        int frames = Math.min(S3kRenderer.MAX_PRESENTATIONS,
                (int) Math.ceil(timeline.project(samples / (double) RATE) * 60));
        int chipSamples = frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        double nativeTone = renderLevel(probe.compile(toneNotes, voicing), chipSamples).powerOn(active, timeline);
        double nativeDrums = renderLevel(probe.compile(drumNotes, voicing), chipSamples).powerOn(active, timeline);
        if (nativeDrums == 0 || nativeTone == 0) return voicing;
        double target = nativeTone * (referenceDrums / referenceTone);
        if (score.inputs().options(score.durationUs()).stockDrums()) {
            return balanceAroundStockKit(voicing, probe, toneNotes, drumNotes, chipSamples, timeline, active,
                    nativeDrums / (referenceDrums / referenceTone), db(target / nativeDrums));
        }
        double correction = db(target / nativeDrums);
        if (Math.abs(correction) <= BALANCE_TOLERANCE_LU) return voicing;

        // PSG volume steps are 2 dB each, and saved drum gains are bounded by 4×.
        int noiseStep = Math.clamp((int) Math.round(correction / 2), -15, 6);
        double dacGain = Math.clamp(amplitude(correction), .125, 4);
        var fitted = apply(voicing, drumNotes, dacGain, noiseStep);
        double rendered = renderLevel(probe.compile(drumNotes, fitted), chipSamples).powerOn(active, timeline);
        // Re-measure after native quantization. DAC can absorb the residual
        // from rounding the PSG level or from rounding/clipping PCM bytes.
        if (rendered > 0 && Math.abs(db(target / rendered)) > QUANTIZATION_TOLERANCE_LU
                && voicing.lanes().containsKey("DAC")) {
            double refinedGain = Math.clamp(dacGain * Math.sqrt(target / rendered), .125, 4);
            var refined = apply(voicing, drumNotes, refinedGain, noiseStep);
            double check = renderLevel(probe.compile(drumNotes, refined), chipSamples).powerOn(active, timeline);
            if (check > 0 && Math.abs(db(target / check)) < Math.abs(db(target / rendered))) {
                fitted = refined;
                rendered = check;
            }
        }
        if (rendered == 0 || Math.abs(db(target / rendered)) >= Math.abs(correction)) return voicing;
        if (correction > 0) {
            var all = new ArrayList<>(toneNotes);
            all.addAll(drumNotes);
            var peak = renderLevel(probe.compile(all, fitted), chipSamples).peak();
            if (peak > PEAK_LIMIT && peak > renderLevel(probe.compile(all, voicing), chipSamples).peak()) return voicing;
        }
        return fitted;
    }

    private static ReconstructionVoicing.Result balanceAroundStockKit(ReconstructionVoicing.Result voicing,
            ReconstructionLevels.Probe probe, List<ReconstructionAllocation.Entry> toneNotes,
            List<ReconstructionAllocation.Entry> drumNotes, int samples, ReconstructionTimeMap timeline,
            boolean[] active, double desiredTone, double correction) throws IOException {
        if (Math.abs(correction) <= BALANCE_TOLERANCE_LU) return voicing;

        // DAC is fixed-level on both cartridges. Adjust the tonal lanes'
        // native volume instead, preserving both stock PCM and its reference.
        double multiplier = Math.clamp(amplitude(-correction), .125, 4);
        var lanes = new LinkedHashMap<>(voicing.lanes());
        toneNotes.stream().map(ReconstructionAllocation.Entry::lane).distinct().forEach(id ->
                lanes.computeIfPresent(id, (key, lane) -> lane.withGain(Math.min(4, lane.gain() * multiplier))));
        var balanced = voicing.withLevels(lanes, voicing.sourceInstruments(), voicing.leadProminenceWarning());
        double tone = renderLevel(probe.compile(toneNotes, balanced), samples).powerOn(active, timeline);
        if (tone == 0 || Math.abs(db(desiredTone / tone)) >= Math.abs(correction)) return voicing;
        if (multiplier > 1) {
            var all = new ArrayList<>(toneNotes);
            all.addAll(drumNotes);
            double peak = renderLevel(probe.compile(all, balanced), samples).peak();
            if (peak > PEAK_LIMIT && peak > renderLevel(probe.compile(all, voicing), samples).peak()) return voicing;
        }
        return balanced;
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
        return base.withLevels(lanes, base.sourceInstruments(), base.leadProminenceWarning());
    }

    private static double db(double power) {
        return 10 * Math.log10(power);
    }

    private static double amplitude(double db) {
        return Math.pow(10, db / 20);
    }
}
