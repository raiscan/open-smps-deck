package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.ProjectCompiler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final int SHORTLIST_LIMIT = 18;
    private static final int CACHE_LIMIT = 24_000;

    record RankedPatch(String id, double score) {}
    private record Key(String asset, int pitch) {}
    private record Probe(int pitch, float[] target, float[] weights) {}
    private static final Map<Key, float[]> TEMPLATES = new ConcurrentHashMap<>();

    private final InstrumentCatalogue catalogue;
    private final List<InstrumentCatalogue.Variant> variants;
    private final VoiceSpectra spectra = new VoiceSpectra();

    VoicePatchShortlist(InstrumentCatalogue catalogue) {
        this.catalogue = catalogue;
        var seen = new HashSet<>(CatalogueMatcher.inferredAzureLakeSlots(catalogue));
        variants = catalogue.variants().stream().sorted(Comparator.comparing(InstrumentCatalogue.Variant::id))
                .filter(variant -> catalogue.asset(variant.assetId()).kind() == FM_VOICE && seen.add(variant.assetId()))
                .toList();
    }

    List<RankedPatch> rank(List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> context,
                           Path mix, Map<Integer, Path> stems, VoiceWindows windows,
                           InstrumentCatalogue.Variant incumbent) throws IOException {
        var available = new ArrayList<>(notes.stream().filter(n -> windows.trainingAttack(n.startTick(), ATTACK_TICKS)).toList());
        var occupied = new java.util.HashMap<String, int[]>();
        available.sort(Comparator.comparingInt((ReconstructionAllocation.Entry note) -> {
            // Prefer attacks with few other attacks or sustaining notes in the mix.
            String stem = stems.containsKey(note.note().part()) ? stems.get(note.note().part()).toString() : "";
            int[] coverage = occupied.computeIfAbsent(stem, ignored -> coverage(context, stems, stem, windows.songFrames()));
            return coverage[note.startTick()];
        }).thenComparing(Comparator.comparingInt((ReconstructionAllocation.Entry n) ->
                Math.min(ATTACK_TICKS, n.endTick() - n.startTick())).reversed()));
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
            var probe = probe(path, end, note);
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

    private RankedPatch rank(InstrumentCatalogue.Variant variant, List<Probe> probes) throws IOException {
        double score = 0;
        for (var probe : probes) score += similarity(probe, template(variant, probe.pitch()));
        return new RankedPatch(variant.id(), probes.isEmpty() ? 0 : score / probes.size());
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

    private Probe probe(Path path, long audioEnd, ReconstructionAllocation.Entry note) throws IOException {
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
        return new Probe(note.note().pitch(), target, weights);
    }

    private float[] template(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        var key = new Key(variant.assetId(), pitch);
        var cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        var project = CataloguePlayback.matchProbe(catalogue, variant.id(), pitch, ATTACK_TICKS, true);
        var rendered = VoiceWindows.renderMono(ProjectCompiler.compileWithAssets(project), ATTACK_TICKS);
        var features = spectra.average(rendered);
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
