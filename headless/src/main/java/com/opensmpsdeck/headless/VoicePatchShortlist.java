package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind.FM_VOICE;
import static com.opensmpsdeck.headless.VoiceSpectra.SAMPLES_PER_TICK;
import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

/** Lane-specific screening using pitch-aligned attack spectra and shared raw patch probes. */
final class VoicePatchShortlist {
    private static final int ATTACK_TICKS = 7;
    private static final int PROBE_LIMIT = 5;
    private static final int GUITAR_ATTACK_TICKS = VoiceAttackShape.SAMPLES / SAMPLES_PER_TICK;
    private static final int GUITAR_PROBE_LIMIT = 32;
    private static final double GUITAR_ATTACK_MARGIN = .025;
    private static final int SHORTLIST_LIMIT = 18;
    private static final int CACHE_LIMIT = 24_000;

    record RankedPatch(String id, double score) {}
    private record Key(String asset, int pitch, TargetGame target) {}
    private record LowProbe(int pitch, LowRegisterSpectrum.Profile profile) {}
    private record Probe(int pitch, float[] target, float[] weights, double exposure) {}
    private record AttackProbe(int pitch, VoiceAttackShape.Profile shape) {}
    private record Template(float[] spectrum, LowRegisterSpectrum.Profile lowProfile) {}
    private record AttackMatch(RankedPatch patch, double loss, double medianRise) {}
    private static final Map<Key, Template> TEMPLATES = new ConcurrentHashMap<>();

    private final InstrumentCatalogue catalogue;
    private final TargetGame target;
    private final List<InstrumentCatalogue.Variant> variants;
    private final VoiceSpectra spectra = new VoiceSpectra();
    private final LowRegisterSpectrum lowSpectrum = new LowRegisterSpectrum();
    private final VoiceAttackShape attackShape = new VoiceAttackShape();

    VoicePatchShortlist(InstrumentCatalogue catalogue, TargetGame target) {
        this.catalogue = catalogue;
        this.target = target;
        var seen = target == TargetGame.S3K
                ? new HashSet<>(CatalogueMatcher.inferredAzureLakeSlots(catalogue)) : new HashSet<String>();
        variants = catalogue.variants().stream().sorted(Comparator.comparing(InstrumentCatalogue.Variant::id))
                .filter(variant -> variant.target() == target)
                .filter(variant -> catalogue.asset(variant.assetId()).kind() == FM_VOICE && seen.add(variant.assetId()))
                .toList();
    }

    RankedPatch leadSynth() throws IOException {
        final int ticks = 24, pitch = 69;
        RankedPatch best = null;
        var measured = new java.util.HashSet<String>();
        for (var variant : variants) {
            if (!measured.add(variant.assetId())) continue;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("lead synth selection cancelled");
            var project = CataloguePlayback.matchProbe(catalogue, variant.id(), pitch, ticks, true, target);
            float[] audio = VoiceWindows.renderMono(project, ticks);
            double early = energy(audio, audio.length / 8, audio.length / 2);
            double held = energy(audio, audio.length / 2, audio.length);
            if (held < 1e-8) continue;
            double fundamental = harmonic(audio, 440);
            double overtones = harmonic(audio, 880) + harmonic(audio, 1320) + harmonic(audio, 1760);
            // Favour audible, on-pitch sustained patches with a distinctive
            // upper spectrum. Impulses, sub-oscillators and noise score poorly.
            double pitched = Math.min(1, (fundamental + overtones) / held);
            double brightness = Math.min(1, overtones / Math.max(1e-8, fundamental));
            double sustain = Math.min(1, held / Math.max(1e-8, early));
            double quality = sustain * pitched * (.35 + .65 * brightness)
                    * Math.min(1, fundamental / (held * .25));
            if (best == null || quality > best.score) best = new RankedPatch(variant.id(), quality);
        }
        return best == null && !variants.isEmpty() ? new RankedPatch(variants.getFirst().id(), 0) : best;
    }

    private static double energy(float[] audio, int from, int to) {
        double total = 0;
        for (int i = from; i < to; i++) total += audio[i] * audio[i];
        return total / (to - from);
    }

    private static double harmonic(float[] audio, double hz) {
        int from = audio.length / 2;
        double re = 0, im = 0;
        for (int i = from; i < audio.length; i++) {
            double phase = Math.TAU * hz * i / VoiceSpectra.RATE;
            re += audio[i] * Math.cos(phase);
            im += audio[i] * Math.sin(phase);
        }
        double length = audio.length - from;
        return 2 * (re * re + im * im) / (length * length);
    }

    List<RankedPatch> rank(List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> context,
                           Path mix, Map<Integer, Path> stems, VoiceWindows windows,
                           InstrumentCatalogue.Variant incumbent, boolean foreground) throws IOException {
        var available = new ArrayList<>(notes.stream().filter(n -> windows.trainingAttack(n.startTick(), ATTACK_TICKS)).toList());
        var occupied = new java.util.HashMap<String, int[]>();
        available.sort(Comparator.comparingInt((ReconstructionAllocation.Entry note) -> {
            // Prefer attacks with few other attacks or sustaining notes in the mix.
            String stem = stems.containsKey(note.note().part()) ? stems.get(note.note().part()).toString() : "";
            int[] coverage = occupied.computeIfAbsent(stem, ignored -> coverage(context, stems, stem, windows.songFrames()));
            return coverage[note.startTick()];
        }).thenComparing(Comparator.comparingInt((ReconstructionAllocation.Entry n) ->
                Math.min(foreground ? 18 : ATTACK_TICKS, n.endTick() - n.startTick())).reversed())
                .thenComparingInt(n -> foreground ? -n.note().velocity() : 0));
        var chosen = new ArrayList<ReconstructionAllocation.Entry>();
        var probes = new ArrayList<Probe>();
        var durations = new java.util.HashMap<Path, Long>();
        for (var note : available) {
            if (chosen.stream().anyMatch(n -> Math.abs(n.startTick() - note.startTick()) < 25)) continue;
            if (chosen.stream().filter(n -> n.note().pitch() == note.note().pitch()).count() >= 2) continue;
            Path path = stems.getOrDefault(note.note().part(), mix);
            Long end = durations.get(path);
            if (end == null) {
                end = ReferenceAudio.durationMicros(path);
                durations.put(path, end);
            }
            var probe = probe(path, end, note, foreground);
            if (probe == null) continue;
            chosen.add(note);
            probes.add(probe);
            if (probes.size() == PROBE_LIMIT) break;
        }
        var ranked = new ArrayList<RankedPatch>();
        var availableVariants = probes.isEmpty() ? variants.stream().limit(1).toList() : variants;
        for (var variant : availableVariants) ranked.add(rank(variant, probes));
        ranked.sort(Comparator.comparingDouble(RankedPatch::score).reversed().thenComparing(RankedPatch::id));
        var shortlist = new ArrayList<>(ranked.subList(0, Math.min(ranked.size(), SHORTLIST_LIMIT)));
        if (incumbent != null && shortlist.stream().noneMatch(patch -> patch.id().equals(incumbent.id()))) {
            if (shortlist.size() == SHORTLIST_LIMIT) shortlist.removeLast();
            shortlist.add(rank(incumbent, probes));
        }
        return List.copyOf(shortlist);
    }

    /**
     * Nominate attacks independently of the longer spectral window, then
     * screen the combined shortlist for stem-supported holds.
     */
    List<RankedPatch> fittingGuitar(List<RankedPatch> candidates,
                                    List<ReconstructionAllocation.Entry> part,
                                    Path stem, VoiceWindows windows,
                                    TargetProfile profile, List<ReconstructionHeldNotes.Example> holds) throws IOException {
        if (candidates.isEmpty()) return candidates;
        var probes = attackProbes(part, stem, windows, profile);
        if (probes.size() < 3)
            return ReconstructionHeldNotes.fittingPatches(catalogue, profile, candidates, holds);

        var attacks = new java.util.LinkedHashMap<String, AttackMatch>();
        for (var candidate : candidates)
            attacks.put(candidate.id(), matchAttack(candidate, probes, null));
        for (var variant : variants) {
            if (!attacks.containsKey(variant.id()))
                attacks.put(variant.id(), matchAttack(new RankedPatch(variant.id(), 0), probes, null));
        }
        var ordered = attacks.values().stream().sorted(Comparator.comparingDouble(AttackMatch::loss)
                .thenComparing(match -> match.patch().id())).toList();
        var shortlist = new ArrayList<>(candidates);
        for (var match : ordered.stream().limit(SHORTLIST_LIMIT).toList())
            if (!shortlist.contains(match.patch())) shortlist.add(match.patch());
        var sustaining = ReconstructionHeldNotes.fittingPatches(catalogue, profile, shortlist, holds);
        var viable = sustaining.stream().map(patch -> attacks.get(patch.id())).toList();
        double bestRise = viable.stream().mapToDouble(AttackMatch::medianRise).min().orElseThrow();
        var timely = viable.stream().filter(match -> match.medianRise() <= bestRise).toList();
        double bestLoss = timely.stream().mapToDouble(AttackMatch::loss).min().orElseThrow();
        // The contextual fit breaks near ties among patches with the same median rise error.
        return timely.stream().filter(match -> match.loss() <= bestLoss + GUITAR_ATTACK_MARGIN)
                .map(match -> new RankedPatch(match.patch().id(), 1 / (1 + match.loss()))).toList();
    }

    boolean keepsGuitarAttack(List<ReconstructionAllocation.Entry> part, Path stem, VoiceWindows windows,
                              TargetProfile profile, RankedPatch original, RankedPatch candidate,
                              ReconstructionVoicing.FmTimbre timbre) throws IOException {
        var probes = attackProbes(part, stem, windows, profile);
        if (probes.size() < 3) return true;
        var before = matchAttack(original, probes, null);
        var after = matchAttack(candidate, probes, timbre);
        return after.medianRise() <= before.medianRise() && after.loss() <= before.loss() + GUITAR_ATTACK_MARGIN;
    }

    private List<AttackProbe> attackProbes(List<ReconstructionAllocation.Entry> part, Path stem,
                                           VoiceWindows windows,
                                           TargetProfile profile) throws IOException {
        double duration = VoiceAttackShape.SAMPLES / (double) VoiceSpectra.RATE;
        double audioEnd = ReferenceAudio.durationMicros(stem) / 1e6;
        var ordered = part.stream().sorted(Comparator.comparingDouble(n -> n.note().start())).toList();
        var exposed = new ArrayList<ReconstructionAllocation.Entry>();
        double previousEnd = 0;
        for (int i = 0; i < ordered.size(); i++) {
            var entry = ordered.get(i);
            var note = entry.note();
            double nextStart = i + 1 < ordered.size() ? ordered.get(i + 1).note().start()
                    : Double.POSITIVE_INFINITY;
            // Reject chords and continuing notes on the same stem. A small
            // boundary tolerance permits adjacent gates with score rounding.
            if (previousEnd <= note.start() + .005 && nextStart >= note.start() + duration
                    && note.run() == null && entry.lane().startsWith("FM")
                    && entry.endTick() - entry.startTick() >= GUITAR_ATTACK_TICKS
                    && note.end() - note.start() >= duration && note.start() + duration <= audioEnd
                    && note.pitch() >= profile.fm().low() && note.pitch() <= profile.fm().high()
                    && windows.trainingAttack(entry.startTick(), GUITAR_ATTACK_TICKS)) exposed.add(entry);
            previousEnd = Math.max(previousEnd, note.end());
        }
        // Spread the probe budget across the part so one run cannot consume it.
        int count = Math.min(GUITAR_PROBE_LIMIT, exposed.size());
        var probes = new ArrayList<AttackProbe>();
        for (int i = 0; i < count; i++) {
            var note = exposed.get(i * exposed.size() / count).note();
            var audio = VoiceSpectra.resample(ReferenceAudio.read(stem, note.start(), duration), VoiceAttackShape.SAMPLES);
            var shape = attackShape.measure(audio);
            if (shape.peak() >= .003) probes.add(new AttackProbe(note.pitch(), shape));
        }
        return probes;
    }

    private AttackMatch matchAttack(RankedPatch patch, List<AttackProbe> probes,
                                    ReconstructionVoicing.FmTimbre timbre) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("attack fitting cancelled");
        var rendered = new java.util.HashMap<Integer, VoiceAttackShape.Profile>();
        var variant = catalogue.match(patch.id(), target).variant();
        double[] rise = new double[probes.size()];
        double loss = 0;
        for (int i = 0; i < probes.size(); i++) {
            var probe = probes.get(i);
            var shape = rendered.get(probe.pitch());
            if (shape == null) {
                var project = CataloguePlayback.fmTimbreProbe(catalogue, variant.id(), probe.pitch(),
                        GUITAR_ATTACK_TICKS, target, timbre);
                shape = attackShape.measure(VoiceWindows.renderMono(project, GUITAR_ATTACK_TICKS));
                rendered.put(probe.pitch(), shape);
            }
            rise[i] = shape.peak() < 1e-6 ? 1 : probe.shape().riseDistance(shape);
            loss += probe.shape().distance(shape);
        }
        Arrays.sort(rise);
        double median = (rise[(rise.length - 1) / 2] + rise[rise.length / 2]) / 2;
        return new AttackMatch(patch, loss / probes.size(), median);
    }

    List<RankedPatch> lowCandidates(List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> context,
                          Path stem, VoiceWindows windows)
            throws IOException {
        if (!LowRegisterSpectrum.lowRegister(notes)) return List.of();
        long end = ReferenceAudio.durationMicros(stem);
        double duration = ATTACK_TICKS / (double) PRESENTATIONS_PER_SECOND;
        var occupied = coverage(context, Map.of(), "", windows.songFrames());
        var candidates = notes.stream()
                .filter(n -> n.note().pitch() <= LowRegisterSpectrum.MAX_PITCH
                        && n.endTick() - n.startTick() >= ATTACK_TICKS - 2
                        && n.note().start() + duration <= end / 1e6
                        && windows.trainingAttack(n.startTick(), ATTACK_TICKS))
                .sorted(Comparator.comparingInt((ReconstructionAllocation.Entry n) -> occupied[n.startTick()])
                        .thenComparing(Comparator.comparingInt((ReconstructionAllocation.Entry n) ->
                                Math.min(ATTACK_TICKS, n.endTick() - n.startTick())).reversed())).toList();
        var probes = new ArrayList<LowProbe>();
        var times = new ArrayList<Integer>();
        for (var note : candidates) {
            int pitch = note.note().pitch();
            if (times.stream().anyMatch(t -> Math.abs(t - note.startTick()) < 25)
                    || probes.stream().filter(p -> p.pitch() == pitch).count() >= 2) continue;
            var audio = VoiceSpectra.resample(ReferenceAudio.read(stem, note.note().start(), duration),
                    ATTACK_TICKS * SAMPLES_PER_TICK);
            var profile = lowSpectrum.profile(audio, pitch);
            if (profile == null) continue;
            probes.add(new LowProbe(pitch, profile));
            times.add(note.startTick());
            if (probes.size() == PROBE_LIMIT) break;
        }
        if (probes.size() < 2) return List.of();
        var ranked = new ArrayList<RankedPatch>();
        for (var variant : variants) ranked.add(new RankedPatch(variant.id(), 1 / (1 + lowDistance(variant, probes) / 100)));
        ranked.sort(Comparator.comparingDouble(RankedPatch::score).reversed().thenComparing(RankedPatch::id));
        return List.copyOf(ranked.subList(0, Math.min(ranked.size(), 8)));
    }

    private double lowDistance(InstrumentCatalogue.Variant variant, List<LowProbe> probes) throws IOException {
        double error = 0;
        for (var probe : probes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("patch shortlist cancelled");
            error += LowRegisterSpectrum.distance(probe.profile(), template(variant, probe.pitch()).lowProfile());
        }
        return error / probes.size();
    }

    private RankedPatch rank(InstrumentCatalogue.Variant variant, List<Probe> probes) throws IOException {
        double score = 0, exposure = 0;
        for (var probe : probes) {
            score += probe.exposure() * similarity(probe, template(variant, probe.pitch()).spectrum());
            exposure += probe.exposure();
        }
        return new RankedPatch(variant.id(), exposure == 0 ? 0 : score / exposure);
    }

    private static int[] coverage(List<ReconstructionAllocation.Entry> notes, Map<Integer, Path> stems,
                                  String stem, int frames) {
        int[] activity = new int[frames + 1];
        for (var note : notes) {
            if (!stem.isEmpty() && (!stems.containsKey(note.note().part())
                    || !stem.equals(stems.get(note.note().part()).toString()))) continue;
            // Weight simultaneous attacks more heavily than continuing notes.
            int start = Math.max(0, note.startTick() - ATTACK_TICKS / 2);
            int end = Math.min(frames, note.startTick() + ATTACK_TICKS / 2);
            activity[start] += 4;
            activity[end] -= 4;
            activity[note.startTick()]++;
            activity[note.endTick()]--;
        }
        for (int i = 1; i <= frames; i++) activity[i] += activity[i - 1];
        return activity;
    }

    private Probe probe(Path path, long audioEnd, ReconstructionAllocation.Entry note, boolean foreground)
            throws IOException {
        double start = note.note().start();
        double duration = ATTACK_TICKS / (double) PRESENTATIONS_PER_SECOND;
        if (start + duration > audioEnd / 1e6) return null;
        int samples = ATTACK_TICKS * SAMPLES_PER_TICK;
        var target = spectra.average(VoiceSpectra.resample(ReferenceAudio.read(path, start, duration), samples));
        if (start >= duration) {
            var before = spectra.average(VoiceSpectra.resample(ReferenceAudio.read(path, start - duration, duration), samples));
            for (int i = 0; i < target.length; i++) target[i] = Math.max(0, target[i] - .7f * before[i]);
        }
        float peak = 0;
        for (var value : target) peak = Math.max(peak, value);
        if (peak < .01) return null;
        var weights = new float[target.length];
        for (int i = 0; i < target.length; i++)
            weights[i] = 1 / (target[i] + .03f * peak);
        double exposure = foreground ? Math.sqrt(peak * Math.min(ATTACK_TICKS, note.endTick() - note.startTick())) : 1;
        // Shared instruments probe PSG spill on a surrogate FM channel. Use the
        // same upper limit as VoiceWindows without changing the actual PSG note.
        int pitch = Math.min(note.note().pitch(), TargetProfile.of(this.target).fm().high());
        return new Probe(pitch, target, weights, exposure);
    }

    private Template template(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        var key = new Key(variant.assetId(), pitch, target);
        var cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        var project = CataloguePlayback.matchProbe(catalogue, variant.id(), pitch, ATTACK_TICKS, true, target);
        var rendered = VoiceWindows.renderMono(project, ATTACK_TICKS);
        var features = new Template(spectra.average(rendered),
                pitch <= LowRegisterSpectrum.MAX_PITCH ? lowSpectrum.profile(rendered, pitch) : null);
        if (TEMPLATES.size() < CACHE_LIMIT) {
            var existing = TEMPLATES.putIfAbsent(key, features);
            if (existing != null) return existing;
        }
        return features;
    }

    private static double similarity(Probe probe, float[] template) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("patch shortlist cancelled");
        double cross = 0, x = 0, y = 0;
        for (int i = 0; i < template.length; i++) {
            double target = probe.target()[i];
            double value = template[i];
            double weight = probe.weights()[i];
            cross += target * value * weight;
            x += value * value * weight;
            y += target * target * weight;
        }
        return x > 0 && y > 0 ? cross * cross / (x * y) : 0;
    }
}
