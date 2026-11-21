package com.opensmpsdeck.headless.bench;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class BenchScorecard {
    private static final int VERSION = 2;
    private static final String DRUM_CLASS = "drumClassAccuracy";
    private static final String LEGACY_DRUM_CLASS = "drumClassAccuracyLegacy";
    record Case(String id, BenchMidi.Profile profile, BenchAudio.Variant variant, Map<String, Double> metrics,
                BenchMetrics.F1 notes, BenchMetrics.F1 offsets, BenchMetrics.F1 melody, BenchMetrics.F1 drums,
                BenchVoice.Score voices, BenchGold.Score gold, String error) {}

    record Group(int cases, Map<String, Double> mean, Map<String, Integer> measured) {}
    record Card(int version, String manifestSha256, String catalogueSha256, boolean holdout, boolean audioRefine,
                Map<String, String> goldInputs, List<Case> cases, Group overall, Map<String, Group> profiles,
                Map<String, Group> variants, Group gold) {}

    private static final List<String> LOWER = List.of("logMelDistance", "releaseEnergy", "voiceSpectralDistance", "leadGap");
    private static final List<String> HEADLINES = List.of("noteF1", "notePrecision", "noteRecall", "offsetF1",
            "melodyF1", "leadFidelityF1", "drumF1", DRUM_CLASS, "voiceTop1", "voiceTop3", "voiceSpectralDistance",
            "voiceSpectralCoverage", "logMelDistance", "onsetCorrelation", "releaseEnergy",
            "leadAttackF1", "leadPitchAccuracy", "leadGap");

    private BenchScorecard() {}

    static Card create(String manifest, String catalogue, boolean holdout, boolean audioRefine,
                       Map<String, String> goldInputs, List<Case> cases) {
        var corpus = cases.stream().filter(c -> c.profile != null).toList();
        var profiles = new TreeMap<String, Group>();
        var variants = new TreeMap<String, Group>();
        for (var profile : BenchMidi.Profile.values()) {
            var matches = corpus.stream().filter(c -> c.profile == profile).toList();
            if (!matches.isEmpty()) profiles.put(profile.toString(), mean(matches));
        }
        for (var variant : BenchAudio.Variant.values()) {
            var matches = corpus.stream().filter(c -> c.variant == variant).toList();
            if (!matches.isEmpty()) variants.put(variant.name(), mean(matches));
        }
        return new Card(VERSION, manifest, catalogue, holdout, audioRefine, goldInputs, cases, mean(corpus), profiles,
                variants, mean(cases.stream().filter(c -> c.profile == null).toList()));
    }

    private static Group mean(List<Case> cases) {
        var values = new TreeMap<String, Double>();
        var counts = new TreeMap<String, Integer>();
        for (var row : cases) {
            if (row.metrics == null) continue;
            row.metrics.forEach((name, value) -> {
                values.merge(name, value, Double::sum);
                counts.merge(name, 1, Integer::sum);
            });
        }
        values.replaceAll((name, total) -> total / counts.get(name));
        return new Group(cases.size(), values, counts);
    }

    static String markdown(Card card) {
        var doc = new StringBuilder("# Reconstruction benchmark\n\n");
        doc.append("Catalogue mode is ").append(card.holdout ? "song holdout" : "in-catalogue")
                .append(". Audio refinement is ").append(card.audioRefine ? "on" : "off").append(".\n\n");
        doc.append("| Group | Cases | Metric | Mean | Measured cases |\n|---|---:|---|---:|---:|\n");
        row(doc, "Overall", card.overall);
        card.profiles.forEach((key, group) -> row(doc, "Profile / " + key, group));
        card.variants.forEach((key, group) -> row(doc, "Audio / " + key, group));
        row(doc, "Gold", card.gold);
        var failed = card.cases.stream().filter(c -> c.error != null).toList();
        if (!failed.isEmpty()) {
            doc.append("\n## Failed cases\n\n");
            for (var failure : failed) doc.append("- ").append(failure.id).append(" — ").append(failure.error).append('\n');
        }
        return doc.toString();
    }

    private static void row(StringBuilder out, String label, Group group) {
        group.mean.forEach((key, value) -> {
            if (key.equals("leadFidelityF1") && group.mean.containsKey("melodyF1")) return;
            metricRow(out, label, group, key, value);
            if (key.equals("melodyF1") && group.mean.containsKey("leadFidelityF1"))
                metricRow(out, label, group, "leadFidelityF1", group.mean.get("leadFidelityF1"));
        });
    }

    private static void metricRow(StringBuilder out, String label, Group group, String key, double value) {
        out.append(String.format(Locale.ROOT, "| %s | %d | %s | %.6f | %d |%n",
                label, group.cases, key, value, group.measured.get(key)));
    }

    record Delta(String metric, Double before, Double after, Double delta, String result) {}
    record Comparison(boolean passed, boolean failedCases, List<Delta> deltas) {}

    static Comparison compare(Card old, Card current, double tolerance) {
        return compare(old, current, tolerance, false);
    }

    static Comparison compare(Card old, Card current, double tolerance, boolean alternateMidi) {
        if (!supported(old) || !supported(current) || old.holdout != current.holdout
                || (!alternateMidi && !old.manifestSha256.equals(current.manifestSha256))
                || !old.goldInputs.equals(current.goldInputs)
                || !caseIds(old).equals(caseIds(current))) {
            throw new IllegalArgumentException("Comparison requires the same corpus, case selection, gold inputs and holdout mode");
        }
        boolean failedCases = old.cases.stream().anyMatch(c -> c.error != null)
                || current.cases.stream().anyMatch(c -> c.error != null);
        boolean failed = failedCases;
        var deltas = new ArrayList<Delta>();
        for (String metric : HEADLINES) {
            boolean historicalDrums = metric.equals(DRUM_CLASS) && (old.version == 1 || current.version == 1);
            String label = historicalDrums ? LEGACY_DRUM_CLASS : metric;
            Double before = headline(old, metric, historicalDrums);
            Double after = headline(current, metric, historicalDrums);
            if (before == null && after == null) continue;
            if (before == null || after == null) {
                boolean lost = after == null;
                failed |= lost && !metric.equals("leadFidelityF1");
                deltas.add(new Delta(label, before, after, null, lost ? "missing" : "new"));
                continue;
            }
            double delta = after - before;
            boolean regression = (LOWER.contains(metric) ? delta : -delta) > tolerance + 1e-12;
            failed |= regression;
            deltas.add(new Delta(label, before, after, delta, regression ? "regression" : "pass"));
        }
        return new Comparison(!failed, failedCases, List.copyOf(deltas));
    }

    private static boolean supported(Card card) {
        return card.version == 1 || card.version == VERSION;
    }

    private static Double headline(Card card, String metric, boolean historicalDrums) {
        String key = historicalDrums && card.version != 1 ? LEGACY_DRUM_CLASS : metric;
        return card.overall.mean.getOrDefault(key, card.gold.mean.get(key));
    }

    static int print(Comparison comparison, PrintStream out) {
        if (comparison.failedCases) out.println("A scorecard contains failed cases.");
        out.println("| Metric | Old | New | Delta | Result |");
        out.println("|---|---:|---:|---:|---|");
        for (var row : comparison.deltas) {
            String delta = row.delta == null ? "—" : String.format(Locale.ROOT, "%+.6f", row.delta);
            out.printf(Locale.ROOT, "| %s | %s | %s | %s | %s |%n",
                    row.metric, number(row.before), number(row.after), delta, row.result);
        }
        return comparison.passed ? 0 : 1;
    }

    private static List<String> caseIds(Card card) {
        return card.cases.stream().map(Case::id).sorted().toList();
    }

    private static String number(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.6f", value);
    }
}
