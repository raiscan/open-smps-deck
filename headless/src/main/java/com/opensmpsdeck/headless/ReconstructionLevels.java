package com.opensmpsdeck.headless;


import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoicing.Lane;
import static com.opensmpsdeck.headless.ReconstructionVoicing.Result;
import static com.opensmpsdeck.headless.ReconstructionVoicing.SourceSound;

/** Fit level ratios on the same stem-active blocks in reference and chip audio. */
final class ReconstructionLevels {
    private static final int RATE = S3kRenderer.SAMPLE_RATE;
    private static final double PEAK_LIMIT = Math.pow(10, -1.0 / 20);
    private static final double BALANCE_TOLERANCE_DB = 2;
    private static final double UNIT_PROBE_THRESHOLD = .125;

    record Level(KWeightedLevel meter, double peak) {
        double powerOn(boolean[] active, ReconstructionTimeMap timeline) {
            return timeline.warped() ? meter.powerOn(active,
                    block -> (timeline.project(KWeightedLevel.blockCenterSeconds(block)) - .2) * 10)
                    : meter.powerOn(active);
        }
    }

    private ReconstructionLevels() {}

    static Result calibrate(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                            ReconstructionAllocation.Result allocation, Result voicing, Set<String> targets)
            throws IOException {
        try {
            return fit(score, catalogue, allocation, voicing, targets);
        } catch (IllegalArgumentException failure) {
            if (score.inputs().audioOnly() == null || !ReconstructionCli.bankOverflow(failure)) throw failure;
            // Assembly can still fit the bank after reducing backing notes.
            return voicing;
        }
    }

    private static Result fit(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                              ReconstructionAllocation.Result allocation, Result voicing, Set<String> targets)
            throws IOException {
        var stems = score.inputs().options(score.durationUs()).stems();
        if (stems.isEmpty()) return voicing;
        var probe = new Probe(score, catalogue, allocation, voicing);
        Path mix = Path.of(score.inputs().wav());
        int samples = Math.min(samples(mix), (int) Math.round(score.durationUs() * (double) RATE / 1_000_000));
        var groups = new LinkedHashMap<Path, List<ReconstructionAllocation.Entry>>();
        var drumStems = new LinkedHashSet<Path>();
        var drums = new ArrayList<ReconstructionAllocation.Entry>();
        for (var entry : probe.notes()) {
            Path stem = stems.get(entry.note().part());
            if (entry.role() == Role.drums) {
                drumStems.add(stem);
                drums.add(entry);
            } else if (stem != null && !stem.equals(mix)) {
                groups.computeIfAbsent(stem, ignored -> new ArrayList<>()).add(entry);
            }
        }
        // Prefer a fixed backing anchor while fitting instrumental groups. If
        // drums lack an independent reference, use the remaining mix instead.
        Path drumStem = drumStems.size() == 1 ? drumStems.getFirst() : null;
        var drumReference = drumStem == null || drumStem.equals(mix) || groups.containsKey(drumStem)
                || samples(drumStem) < samples ? null : referenceLevel(List.of(drumStem), List.of(), samples);
        var nativeDrums = drumReference == null ? null : probe.render(drums, voicing);
        var leadStems = new ArrayList<Path>();
        var leadNotes = new ArrayList<ReconstructionAllocation.Entry>();
        for (var group : groups.entrySet()) {
            var notes = group.getValue();
            if (samples(group.getKey()) < samples || drumStems.contains(group.getKey())
                    || (!targets.isEmpty() && notes.stream().anyMatch(note -> !targets.contains(note.lane())))) continue;
            // A stem shared with backing still supports a group fit, but
            // cannot supply an independent FM1-to-backing target.
            if (notes.stream().allMatch(note -> note.role() == Role.melody && "FM1".equals(note.lane()))) {
                leadStems.add(group.getKey());
                leadNotes.addAll(notes);
                continue;
            }
            var reference = referenceLevel(List.of(group.getKey()), List.of(), samples);
            var active = reference.meter().activeBlocks();
            double referencePower = reference.meter().powerOn(active);
            if (referencePower == 0) continue;
            double target = drumReference == null ? 0 : target(referencePower, drumReference.meter().powerOn(active),
                    nativeDrums.powerOn(active, probe.timeline()));
            if (target == 0) {
                target = backingTarget(probe, mix, List.of(group.getKey()), notes, voicing, samples, active, reference);
            }
            voicing = adjust(probe, notes, voicing, active, target, false);
        }
        if (leadNotes.isEmpty()) return voicing;
        var reference = referenceLevel(leadStems, List.of(), samples);
        var active = reference.meter().activeBlocks();
        double target = backingTarget(probe, mix, leadStems, leadNotes, voicing, samples, active, reference);
        return adjust(probe, leadNotes, voicing, active, target, true);
    }

    private static double backingTarget(Probe probe, Path mix, List<Path> stems, List<ReconstructionAllocation.Entry> group,
                                        Result voicing, int samples, boolean[] active, Level reference) throws IOException {
        var selected = Set.copyOf(group);
        var backing = probe.notes().stream().filter(note -> !selected.contains(note)).toList();
        if (backing.isEmpty()) return 0;
        return target(reference.meter().powerOn(active), referenceLevel(List.of(mix), stems, samples).meter().powerOn(active),
                probe.render(backing, voicing).powerOn(active, probe.timeline()));
    }

    private static double target(double reference, double referenceBacking, double backing) {
        if (Math.min(reference, Math.min(referenceBacking, backing)) <= KWeightedLevel.ABSOLUTE_GATE) return 0;
        return backing * reference / referenceBacking;
    }

    private static Result adjust(Probe probe, List<ReconstructionAllocation.Entry> notes, Result before,
                                 boolean[] active, double target, boolean lead) throws IOException {
        if (target <= 0) return before;
        double original = probe.render(notes, before).powerOn(active, probe.timeline());
        double error = db(target / Math.max(1e-12, original));
        if (Math.abs(error) <= BALANCE_TOLERANCE_DB) return before;

        var gains = new GainGroup(before, notes, probe.notes());
        // Near-zero spectral gains can be below the chip's useful range. Probe
        // at unity so the estimate comes from the patch, not its volume floor.
        double anchor = gains.max() < UNIT_PROBE_THRESHOLD ? 1 : gains.max();
        double power = anchor == gains.max() ? original
                : probe.render(notes, gains.at(anchor)).powerOn(active, probe.timeline());
        if (power <= 0) return before;
        double desired = Math.min(4, anchor * Math.sqrt(target / power));
        var best = before;
        double bestPower = original;
        double low = 0, high = 4;
        for (int pass = 0; pass < 5; pass++) {
            var candidate = gains.at(desired);
            double measured = probe.render(notes, candidate).powerOn(active, probe.timeline());
            boolean safe = measured <= original || probe.render(probe.notes(), candidate).peak() <= PEAK_LIMIT;
            if (safe && measured > 0 && Math.abs(db(target / measured)) < Math.abs(db(target / Math.max(1e-12, bestPower)))
                    && (!lead || error < 0 || measured > bestPower)) {
                best = candidate;
                bestPower = measured;
            }
            if (bestPower > 0 && Math.abs(db(target / Math.max(1e-12, bestPower))) <= BALANCE_TOLERANCE_DB) break;
            if (!safe || measured > target) high = desired;
            else low = desired;
            if (high - low < .01) break;
            double estimate = measured > 0 ? desired * Math.sqrt(target / measured) : high;
            desired = estimate > low + .01 && estimate < high - .01 ? estimate : (low + high) / 2;
        }
        if (!lead) return best;
        double remaining = db(target / Math.max(1e-12, bestPower));
        String warning = bestPower > original ? String.format(Locale.ROOT,
                "FM1 lead prominence: raised the lead %.1f dB on stem-active blocks (%.1f dB below target before correction).",
                db(bestPower / Math.max(1e-12, original)), error) : null;
        if (remaining > BALANCE_TOLERANCE_DB) {
            String residual = String.format(Locale.ROOT,
                    "FM1 lead prominence: lead remains %.1f dB below the stem-to-backing balance at available gain and headroom.",
                    remaining);
            warning = warning == null ? residual : warning + " " + residual;
        }
        return best.withLevels(best.lanes(), best.sourceInstruments(), warning);
    }

    private static double db(double ratio) { return 10 * Math.log10(Math.max(1e-12, ratio)); }

    private static final class GainGroup {
        private final Result base;
        private final Map<String, Set<Integer>> laneParts = new LinkedHashMap<>();
        private final Set<String> sourceIds = new LinkedHashSet<>();
        private final Map<String, List<ReconstructionAllocation.Entry>> byLane;
        private final double max;

        GainGroup(Result base, List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> all) {
            this.base = base;
            var allocation = new ReconstructionAllocation.Result(base.version(), all);
            byLane = ReconstructionVoicing.byLane(allocation);
            double loudest = 0;
            // A scoped refit cannot change a source sound also used on an
            // untargeted lane. In that case change just the selected lane trim.
            var selected = Set.copyOf(notes);
            var sharedOutside = new LinkedHashSet<Integer>();
            for (var note : all) {
                if (note.role() != Role.drums && !selected.contains(note)
                        && base.sourceSound(note.lane(), note.note().part()) != null) sharedOutside.add(note.note().part());
            }
            for (var note : notes) {
                String laneId = note.lane();
                int part = note.note().part();
                var lane = base.lanes().get(laneId);
                var sound = base.sourceSound(laneId, part);
                if (sound != null && !sharedOutside.contains(part)) {
                    sourceIds.add(ReconstructionVoicePlan.SourceInstrument.id(part));
                    loudest = Math.max(loudest, sound.fm().gain());
                } else {
                    laneParts.computeIfAbsent(laneId, ignored -> new LinkedHashSet<>()).add(part);
                    loudest = Math.max(loudest, lane.gainFor(part));
                }
            }
            max = loudest;
        }

        double max() { return max; }

        Result at(double level) {
            var sources = base.sourceInstruments() == null ? null : new LinkedHashMap<>(base.sourceInstruments());
            var lanes = new LinkedHashMap<>(base.lanes());
            sourceIds.forEach(id -> {
                var sound = sources.get(id);
                sources.put(id, new SourceSound(sound.fm().withGain(scaled(sound.fm().gain(), level)),
                        sound.psgVariantId(), sound.psgVolumeOffset()));
            });
            laneParts.forEach((id, selected) -> {
                var lane = lanes.get(id);
                var parts = new TreeMap<Integer, Double>();
                for (var note : byLane.get(id)) {
                    if (note.role() == Role.drums) continue;
                    int part = note.note().part();
                    parts.put(part, selected.contains(part) ? scaled(lane.gainFor(part), level) : lane.gainFor(part));
                }
                lanes.put(id, withEffectiveGains(lane, parts, selected.size() == parts.size()));
            });
            return base.withLevels(lanes, sources, base.leadProminenceWarning());
        }

        private double scaled(double gain, double level) {
            return max < UNIT_PROBE_THRESHOLD ? level : Math.min(4, level * gain / max);
        }

        private static Lane withEffectiveGains(Lane lane, Map<Integer, Double> parts, boolean wholeLane) {
            double high = parts.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            double low = parts.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            if (high == low) return lane.withGain(high).withPartGains(Map.of());
            double base = wholeLane ? Math.clamp(lane.gain(), high / 4, Math.max(high / 4, low * 4)) : lane.gain();
            if (base == 0) return lane;
            parts.replaceAll((part, gain) -> Math.clamp(gain / base, .25, 4));
            return lane.withGain(base).withPartGains(parts);
        }
    }

    static int samples(Path wav) throws IOException {
        return (int) Math.min(Integer.MAX_VALUE, ReferenceAudio.durationMicros(wav) * RATE / 1_000_000);
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
        return new Level(loudness, 0);
    }

    static Level renderLevel(TargetPlayback compiled, int samples) throws IOException {
        var loudness = new KWeightedLevel();
        int[] peak = {0};
        int[] remaining = {samples * 2};
        int presentations = Math.ceilDiv(samples, S3kRenderer.STEREO_FRAMES_PER_PRESENTATION);
        compiled.render(presentations, pcm -> {
            checkCancelled();
            int length = Math.min(pcm.length, remaining[0]);
            for (int i = 0; i < length; i += 2) {
                loudness.add(pcm[i] / 32768.0, pcm[i + 1] / 32768.0);
                peak[0] = Math.max(peak[0], Math.max(Math.abs((int) pcm[i]), Math.abs((int) pcm[i + 1])));
            }
            remaining[0] -= length;
        });
        return new Level(loudness, peak[0] / 32768.0);
    }

    static final class Probe {
        private final ReconstructionStages.Score score;
        private final InstrumentCatalogue catalogue;
        private final ReconstructionAllocation.Result allocation;
        private final ReconstructionTimeMap timeline;
        private final Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches;
        private final List<ReconstructionAllocation.Entry> notes;

        Probe(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
              ReconstructionAllocation.Result allocation, Result voicing) {
            this.score = score;
            this.catalogue = catalogue;
            this.allocation = allocation;
            timeline = ReconstructionTimeMap.of(score);
            matches = ReconstructionCli.voicedMatches(catalogue, allocation, voicing).byLane();
            notes = allocation.notes().stream().filter(note -> note.lane() != null && playable(note)).toList();
        }

        ReconstructionTimeMap timeline() { return timeline; }
        List<ReconstructionAllocation.Entry> notes() { return notes; }

        private boolean playable(ReconstructionAllocation.Entry entry) {
            if (entry.role() == Role.drums && entry.lane().equals("PSG3")) return true;
            var key = new ReconstructionVoices.Key(entry.note().part(),
                    entry.lane().equals("DAC") ? entry.note().pitch() : null);
            var choice = matches.get(entry.lane()).get(key);
            return choice != null && choice.assetId() != null;
        }

        Level render(List<ReconstructionAllocation.Entry> notes, Result voicing) throws IOException {
            int frames = Math.min(S3kRenderer.MAX_PRESENTATIONS, timeline.frames());
            return renderLevel(compile(notes, voicing), frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION);
        }

        TargetPlayback compile(List<ReconstructionAllocation.Entry> notes, Result voicing) throws IOException {
            var ordered = notes.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList();
            var playback = ReconstructionRetriggers.articulate(ordered, score.retriggers(), timeline, voicing::articulation);
            var group = new ReconstructionAllocation.Result(allocation.version(), playback.notes(), allocation.timeBase(),
                    null, null, null, null, allocation.chordVoicings());
            var source = new S3kReconstruction.Score(score.source().parts(), List.of(), List.of(), score.profile());
            var arrangement = S3kReconstruction.arrangeAllocated("Level probe", score.inputs().ticksPerBeat(),
                    timeline.ticks(), source, group, null, catalogue, matches, Map.of(), voicing.lanes(),
                    true, false, Map.of(), playback.origins(), Map.of(), null, Map.of(), voicing.sourceInstruments());
            return TargetPlayback.prepare(arrangement.project());
        }
    }

    private static void checkCancelled() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("level fit cancelled");
    }
}
