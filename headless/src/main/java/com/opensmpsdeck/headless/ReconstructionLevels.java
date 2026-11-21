package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoicing.Lane;

/** Match the relative output levels of independently mapped stems. */
final class ReconstructionLevels {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final double AUDIO_FLOOR = KWeightedLevel.ABSOLUTE_GATE;
    private static final double HALF_FM_STEP = Math.pow(10, .375 / 20);
    private static final double BALANCE_TOLERANCE = Math.pow(10, 2.0 / 20);
    private static final double PEAK_LIMIT = Math.pow(10, -1.0 / 20);

    record Level(double power, double peak) {}

    private ReconstructionLevels() {}

    static void calibrate(int frames, S3kReconstruction.Score source, InstrumentCatalogue catalogue,
                          Map<String, List<ReconstructionAllocation.Entry>> lanes, Path mix,
                          Map<Integer, Path> stems, Map<String, Lane> settings) throws IOException {
        if (stems.isEmpty()) return;
        var original = new LinkedHashMap<>(settings);
        var bass = new ArrayList<ReconstructionAllocation.Entry>();
        var rest = new ArrayList<ReconstructionAllocation.Entry>();
        var bassStems = new LinkedHashSet<Path>();
        var tonal = new LinkedHashMap<Path, List<ReconstructionAllocation.Entry>>();
        var drums = new ArrayList<ReconstructionAllocation.Entry>();
        var drumStems = new LinkedHashSet<Path>();
        for (var lane : lanes.entrySet()) {
            var notes = lane.getValue();
            Path dedicated = bassStem(notes, settings.get(lane.getKey()), mix, stems);
            if (dedicated != null) {
                bassStems.add(dedicated);
                bass.addAll(notes);
                continue;
            }
            rest.addAll(notes);
            for (var entry : notes) {
                var stem = stems.get(entry.note().part());
                if (entry.role() == Role.drums) {
                    drumStems.add(stem);
                    drums.add(entry);
                } else if (stem != null && !stem.equals(mix)) {
                    tonal.computeIfAbsent(stem, ignored -> new ArrayList<>()).add(entry);
                }
            }
        }
        // Bass can share a stem with notes on other lanes. Calibrate that stem
        // only once against the rest of the mix.
        tonal.keySet().removeAll(bassStems);
        Path drumStem = drumStems.size() == 1 && !drumStems.contains(mix) ? drumStems.getFirst() : null;
        int samples = Math.min(frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION, samples(mix));
        var gains = new TreeMap<String, Map<Integer, Double>>();
        if (drumStem != null && !tonal.containsKey(drumStem) && samples(drumStem) >= samples && !tonal.isEmpty()) {
            double drumPower = referenceLevel(List.of(drumStem), List.of(), samples).power();
            double renderedDrums = renderLevel(frames, samples, source, catalogue, drums, original).power();
            for (var group : tonal.entrySet()) {
                if (samples(group.getKey()) < samples || Math.min(drumPower, renderedDrums) < AUDIO_FLOOR) continue;
                double target = referenceLevel(List.of(group.getKey()), List.of(), samples).power();
                double rendered = renderLevel(frames, samples, source, catalogue, group.getValue(), original).power();
                double gain = adjustment(target, rendered, drumPower, renderedDrums);
                if (gain < 1 / BALANCE_TOLERANCE || gain > BALANCE_TOLERANCE)
                    setGain(gains, group.getValue(), gain);
            }
        }
        applyGains(original, settings, gains);

        int common = samples;
        for (var stem : bassStems) common = Math.min(common, samples(stem));
        if (!bass.isEmpty() && !rest.isEmpty() && common >= samples * .95) {
            var referenceBass = referenceLevel(bassStems, List.of(), common);
            var referenceRest = referenceLevel(List.of(mix), bassStems, common);
            var nativeRest = renderLevel(frames, common, source, catalogue, rest, settings);
            var nativeBass = renderLevel(frames, common, source, catalogue, bass, settings);
            double gain = adjustment(referenceBass.power(), nativeBass.power(), referenceRest.power(), nativeRest.power());
            // Only boost when the sum of the isolated peaks leaves room. Cut
            // bass when it is too high, preserving the rest of the arrangement.
            if (gain > 1 && nativeBass.peak() > 0) {
                double headroom = (PEAK_LIMIT - nativeRest.peak()) / nativeBass.peak();
                gain = Math.clamp(gain, 1, Math.max(1, headroom));
            }
            if (gain < 1 / HALF_FM_STEP || gain > HALF_FM_STEP) setGain(gains, bass, gain);
        }
        applyGains(original, settings, gains);

        if (gains.values().stream().flatMap(parts -> parts.values().stream()).anyMatch(gain -> gain > 1)) {
            var all = new ArrayList<>(rest);
            all.addAll(bass);
            if (renderLevel(frames, samples, source, catalogue, all, settings).peak() > PEAK_LIMIT) {
                // Never commit a boost that pushes the rendered mix past the
                // headroom limit. Keep level cuts and the original drum kit.
                gains.values().forEach(parts -> parts.values().removeIf(gain -> gain > 1));
                applyGains(original, settings, gains);
            }
        }
    }

    private static Path bassStem(List<ReconstructionAllocation.Entry> notes, Lane setting, Path mix,
                                 Map<Integer, Path> stems) {
        if (setting.role() != Role.bass || setting.variantId() == null || !LowRegisterSpectrum.lowRegister(notes))
            return null;
        var ticks = new LinkedHashMap<Path, Long>();
        long total = 0;
        for (var note : notes) {
            long length = note.endTick() - note.startTick();
            total += length;
            var path = stems.get(note.note().part());
            if (path != null && !path.equals(mix)) ticks.merge(path, length, Long::sum);
        }
        for (var stem : ticks.entrySet())
            if (3 * stem.getValue() >= 2 * total) return stem.getKey();
        return null;
    }

    static int samples(Path wav) throws IOException {
        return (int) Math.min(Integer.MAX_VALUE, ReferenceAudio.durationMicros(wav) * RATE / 1_000_000);
    }

    private static double adjustment(double reference, double rendered, double referenceRest, double nativeRest) {
        if (Math.min(Math.min(reference, rendered), Math.min(referenceRest, nativeRest)) < AUDIO_FLOOR) return 1;
        return Math.clamp(Math.sqrt(reference / rendered * nativeRest / referenceRest), .25, 4);
    }

    private static void setGain(Map<String, Map<Integer, Double>> gains,
                                List<ReconstructionAllocation.Entry> notes, double gain) {
        for (var entry : notes) {
            if (entry.role() == Role.drums) continue;
            gains.computeIfAbsent(entry.lane(), ignored -> new TreeMap<>()).put(entry.note().part(), gain);
        }
    }

    private static void applyGains(Map<String, Lane> original, Map<String, Lane> settings,
                                   Map<String, Map<Integer, Double>> gains) {
        original.forEach((id, lane) -> {
            var parts = gains.get(id);
            settings.put(id, parts == null || parts.isEmpty() ? lane : lane.withPartGains(parts));
        });
    }

    static Level referenceLevel(Iterable<Path> stems, Iterable<Path> subtract, int samples) throws IOException {
        var loudness = new KWeightedLevel();
        for (int start = 0; start < samples; start += RATE) {
            checkCancelled();
            int length = Math.min(RATE, samples - start);
            var stereo = new double[length * 2];
            for (var stem : stems) {
                var audio = ReferenceAudio.stereoRange(stem, RATE, start, length);
                for (int i = 0; i < stereo.length; i++) stereo[i] += audio[i];
            }
            for (var stem : subtract) {
                var audio = ReferenceAudio.stereoRange(stem, RATE, start, length);
                for (int i = 0; i < stereo.length; i++) stereo[i] -= audio[i];
            }
            for (int i = 0; i < stereo.length; i += 2) loudness.add(stereo[i], stereo[i + 1]);
        }
        return new Level(loudness.power(), 0);
    }

    private static Level renderLevel(int frames, int samples, S3kReconstruction.Score source,
                                      InstrumentCatalogue catalogue, List<ReconstructionAllocation.Entry> group,
                                      Map<String, Lane> settings) throws IOException {
        var allocation = new ReconstructionAllocation.Result(ReconstructionStages.VERSION,
                group.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList());
        var choices = new LinkedHashMap<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>>();
        ReconstructionVoicing.byLane(allocation).forEach((lane, notes) ->
                choices.put(lane, ReconstructionVoiceStage.choices(catalogue, settings.get(lane), notes)));
        boolean playable = choices.values().stream().flatMap(choicesForLane -> choicesForLane.values().stream())
                .anyMatch(choice -> choice.assetId() != null)
                || group.stream().anyMatch(entry -> entry.role() == Role.drums && entry.lane().equals("PSG3"));
        if (!playable || samples == 0) return new Level(0, 0);
        var project = S3kReconstruction.arrangeAllocated("Level probe", 30, frames, source, allocation,
                null, catalogue, choices, Map.of(), settings, true).project();
        return renderLevel(ProjectCompiler.compileWithAssets(project), samples);
    }

    static Level renderLevel(CompiledProject compiled, int samples) throws IOException {
        var loudness = new KWeightedLevel();
        int[] peak = {0};
        int[] remaining = {samples * 2};
        int presentations = Math.ceilDiv(samples, S3kRenderer.STEREO_FRAMES_PER_PRESENTATION);
        S3kRenderer.render(compiled, presentations, pcm -> {
            checkCancelled();
            int length = Math.min(pcm.length, remaining[0]);
            for (int i = 0; i < length; i += 2) {
                loudness.add(pcm[i] / 32768.0, pcm[i + 1] / 32768.0);
                peak[0] = Math.max(peak[0], Math.max(Math.abs((int) pcm[i]), Math.abs((int) pcm[i + 1])));
            }
            remaining[0] -= length;
        });
        return new Level(loudness.power(), peak[0] / 32768.0);
    }

    private static void checkCancelled() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("level fit cancelled");
    }
}
