package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.AudioFingerprint;
import com.opensmpsdeck.headless.CataloguePlayback;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.ProjectCompiler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BenchVoice {
    record Channel(int matched, Integer top1, Integer top3, Integer spectralCompared, Double spectralDistance) {}
    record Score(Map<String, Channel> channels, int matched, Integer top1, Integer top3, Integer compared, Double distance) {}

    private record Probe(String hash, int pitch) {}
    private static final int GATE = 12;

    private final InstrumentCatalogue catalogue;
    private final Map<String, String> variants = new HashMap<>();
    private final Map<Probe, AudioFingerprint> probes = new HashMap<>();

    BenchVoice(InstrumentCatalogue catalogue) {
        this.catalogue = catalogue;
        for (var variant : catalogue.variants()) {
            if (variant.assetId().startsWith("fm-voice-")) {
                variants.putIfAbsent(variant.assetId().substring("fm-voice-".length()), variant.id());
            }
        }
    }

    Score score(List<BenchMetrics.Event> truth, List<BenchMetrics.Event> estimate, List<BenchMetrics.Pair> pairs,
                BenchReconstruction.Attribution attribution, boolean holdout) throws IOException {
        var totals = new Count();
        var channels = new TreeMap<String, Count>();
        truth.stream().filter(note -> note.lane().startsWith("FM"))
                .forEach(note -> channels.computeIfAbsent(note.lane(), ignored -> new Count()));
        for (var pair : pairs) {
            var a = truth.get(pair.truth());
            var b = estimate.get(pair.estimate());
            if (a.hash() == null || !a.lane().startsWith("FM")) continue;
            boolean top1 = !holdout && a.hash().equals(b.hash());
            boolean top3 = !holdout && attribution.shortlist(b).stream().limit(3)
                    .anyMatch(candidate -> candidate.assetId().equals("fm-voice-" + a.hash()));
            Double distance = holdout ? distance(a, b) : null;
            totals.add(top1, top3, distance);
            channels.get(a.lane()).add(top1, top3, distance);
        }
        var output = new TreeMap<String, Channel>();
        channels.forEach((lane, count) -> output.put(lane, new Channel(count.matched, holdout ? null : count.top1, holdout ? null : count.top3,
                holdout ? count.compared : null, count.mean())));
        return new Score(output, totals.matched, holdout ? null : totals.top1, holdout ? null : totals.top3,
                holdout ? totals.compared : null, totals.mean());
    }

    private Double distance(BenchMetrics.Event a, BenchMetrics.Event b) throws IOException {
        if (!variants.containsKey(a.hash()) || b.hash() == null || !variants.containsKey(b.hash())
                || a.pitch() < 12 || a.pitch() > 106) return null;
        return probe(a.hash(), a.pitch()).distance(probe(b.hash(), a.pitch())).spectral();
    }

    private AudioFingerprint probe(String hash, int pitch) throws IOException {
        var key = new Probe(hash, pitch);
        var fingerprint = probes.get(key);
        if (fingerprint != null) return fingerprint;
        // A clean recipe removes provenance-dependent audition effects from this same-note comparison.
        var original = catalogue.asset("fm-voice-" + hash);
        var builder = new InstrumentCatalogue.Builder();
        String asset = builder.asset(original.kind(), original.name(), original.bytes());
        String variant = builder.variant(asset, original.name(), InstrumentCatalogue.Recipe.plain(), null);
        var project = CataloguePlayback.matchProbe(builder.build(), variant, pitch, GATE);
        int width = S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        float[] audio = new float[GATE * width];
        int[] start = {0};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), GATE, pcm -> {
            for (int i = 0; i < width; i++) audio[start[0]++] = (pcm[2 * i] + pcm[2 * i + 1]) / 65536.0f;
        });
        fingerprint = AudioFingerprint.extract(audio, S3kRenderer.SAMPLE_RATE);
        probes.put(key, fingerprint);
        return fingerprint;
    }

    private static final class Count {
        int matched, top1, top3, compared;
        double sum;

        void add(boolean first, boolean shortlist, Double distance) {
            matched++;
            if (first) top1++;
            if (shortlist) top3++;
            if (distance != null) {
                sum += distance;
                compared++;
            }
        }

        Double mean() { return compared == 0 ? null : sum / compared; }
    }
}
