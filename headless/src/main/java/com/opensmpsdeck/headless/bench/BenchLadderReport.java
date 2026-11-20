package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class BenchLadderReport {
    private static final List<String> METRICS = List.of("melodyF1", "noteF1", "onsetCorrelation", "leadAttackF1",
            "leadPitchAccuracy", "leadGap", "voiceSpectralDistance", "voiceSpectralCoverage");

    record Source(BenchLadderRun.Row row, Path directory) {}
    record Aggregate(String game, BenchLadder.Rung rung, int songs, Map<String, Double> means,
                     Map<String, Integer> measured) {}
    record Drop(String metric, BenchLadder.Rung from, BenchLadder.Rung to, int songs, double delta) {}
    record LossExample(String song, BenchLadderTrace.Example note) {}
    record StageLoss(String stage, int attacks, Map<String, Integer> reasons, List<LossExample> examples) {}
    record Report(List<Aggregate> aggregate, List<Drop> biggestDrops, List<StageLoss> exactLosses,
                  List<StageLoss> exactLeadLosses, List<BenchLadderRun.Row> songs, List<BenchLadderRun.Row> torture) {}

    private BenchLadderReport() {}

    static int write(List<Path> outputs, Path target, PrintStream out) throws IOException {
        var sources = new ArrayList<Source>();
        var seen = new HashSet<String>();
        for (Path root : outputs) {
            try (var paths = Files.walk(root.resolve("cases"))) {
                for (Path file : paths.filter(p -> p.getFileName().toString().equals("ladder-case.json")).sorted().toList()) {
                    var row = BenchFiles.read(file, BenchLadderRun.Row.class);
                    if (!seen.add(row.torture() + ":" + row.id()))
                        throw new IllegalArgumentException("Duplicate ladder result: " + row.id());
                    sources.add(new Source(row, file.getParent()));
                }
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("No ladder case results were found");
        sources.sort(Comparator.comparing((Source s) -> s.row().torture())
                .thenComparing(s -> s.row().game()).thenComparingInt(s -> s.row().song())
                .thenComparing(s -> s.row().rung()));
        var songs = sources.stream().map(Source::row).filter(row -> !row.torture()).toList();
        var torture = sources.stream().map(Source::row).filter(BenchLadderRun.Row::torture).toList();
        var report = new Report(aggregate(songs), drops(songs), losses(songs, false), losses(songs, true), songs, torture);
        Files.createDirectories(target);
        for (String name : List.of("ladder-report.json", "ladder-report.md", "listening.html")) {
            if (Files.exists(target.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Ladder report already exists at " + target.resolve(name));
        }
        Files.writeString(target.resolve("ladder-report.json"), BenchFiles.JSON.toJson(report) + "\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(target.resolve("ladder-report.md"), markdown(report), StandardOpenOption.CREATE_NEW);
        listening(sources, target.resolve("listening.html"));
        out.printf("Wrote ladder report and listening index for %d case(s) to %s%n", sources.size(), target);
        return 0;
    }

    static List<Aggregate> aggregate(List<BenchLadderRun.Row> songs) {
        var rows = new ArrayList<Aggregate>();
        for (var rung : BenchLadder.Rung.values()) addAggregate(rows, "all", rung, songs);
        for (var game : BenchCartridge.Game.values()) {
            var sameGame = songs.stream().filter(row -> row.game() == game).toList();
            for (var rung : BenchLadder.Rung.values()) addAggregate(rows, game.name(), rung, sameGame);
        }
        return List.copyOf(rows);
    }

    private static void addAggregate(List<Aggregate> rows, String game, BenchLadder.Rung rung,
                                     List<BenchLadderRun.Row> songs) {
        var items = songs.stream().filter(row -> row.rung() == rung).toList();
        if (items.isEmpty()) return;
        var means = new LinkedHashMap<String, Double>();
        var measured = new LinkedHashMap<String, Integer>();
        for (String metric : METRICS) {
            var values = items.stream().map(row -> value(row, metric)).filter(java.util.Objects::nonNull).toList();
            measured.put(metric, values.size());
            values.stream().mapToDouble(Double::doubleValue).average().ifPresent(mean -> means.put(metric, mean));
        }
        rows.add(new Aggregate(game, rung, items.size(), means, measured));
    }

    static List<Drop> drops(List<BenchLadderRun.Row> songs) {
        var keys = new TreeMap<String, Map<BenchLadder.Rung, BenchLadderRun.Row>>();
        for (var row : songs) keys.computeIfAbsent(songKey(row), ignored -> new java.util.EnumMap<>(BenchLadder.Rung.class))
                .put(row.rung(), row);
        var result = new ArrayList<Drop>();
        for (var metric : METRICS) {
            Drop worst = null;
            var rungs = BenchLadder.Rung.values();
            // R3 and R4 share the same degraded audio and are alternative MIDI sources.
            var comparisons = List.of(List.of(rungs[0], rungs[1]), List.of(rungs[1], rungs[2]),
                    List.of(rungs[2], rungs[3]), List.of(rungs[2], rungs[4]), List.of(rungs[4], rungs[5]));
            for (var pair : comparisons) {
                var from = pair.getFirst();
                var to = pair.getLast();
                var deltas = keys.values().stream().filter(row -> value(row.get(from), metric) != null
                                && value(row.get(to), metric) != null)
                        .mapToDouble(row -> value(row.get(to), metric) - value(row.get(from), metric)).toArray();
                if (deltas.length == 0) continue;
                double delta = java.util.Arrays.stream(deltas).average().orElseThrow();
                double penalty = lowerIsBetter(metric) ? delta : -delta;
                if (penalty > 0 && (worst == null || penalty > Math.abs(worst.delta())))
                    worst = new Drop(metric, from, to, deltas.length, delta);
            }
            if (worst != null) result.add(worst);
        }
        return List.copyOf(result);
    }

    private static boolean lowerIsBetter(String metric) {
        return metric.equals("leadGap") || metric.equals("voiceSpectralDistance");
    }

    private static Double value(BenchLadderRun.Row row, String metric) {
        return row == null || row.metrics() == null ? null : row.metrics().get(metric);
    }

    private static List<StageLoss> losses(List<BenchLadderRun.Row> songs, boolean lead) {
        var sums = new TreeMap<String, Integer>();
        var reasons = new TreeMap<String, Map<String, Integer>>();
        var examples = new TreeMap<String, List<LossExample>>();
        for (var row : songs) {
            if (row.rung() != BenchLadder.Rung.EXACT || row.trace() == null) continue;
            var waterfall = lead ? row.trace().lead() : row.trace().allNotes();
            waterfall.lost().forEach((stage, loss) -> {
                sums.merge(stage, loss.count(), Integer::sum);
                var reason = reasons.computeIfAbsent(stage, ignored -> new TreeMap<>());
                loss.reasons().forEach((key, count) -> reason.merge(key, count, Integer::sum));
                var samples = examples.computeIfAbsent(stage, ignored -> new ArrayList<>());
                if (!loss.examples().isEmpty() && samples.size() < 3)
                    samples.add(new LossExample(songKey(row), loss.examples().getFirst()));
            });
        }
        return sums.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                .map(entry -> new StageLoss(entry.getKey(), entry.getValue(), reasons.get(entry.getKey()),
                        examples.get(entry.getKey()))).toList();
    }

    private static String markdown(Report report) {
        var text = new StringBuilder("# Reconstruction ladder\n\n");
        text.append("Mean per-song scores. Missing measurements are shown as —. Lead gap is in seconds per minute.\n\n");
        text.append("| Game | Rung | Songs | ").append(String.join(" | ", METRICS)).append(" |\n")
                .append("| --- | --- | ---: |").append(" ---: |".repeat(METRICS.size())).append('\n');
        for (var row : report.aggregate()) {
            text.append("| ").append(row.game()).append(" | ").append(label(row.rung())).append(" | ").append(row.songs());
            for (String key : METRICS) text.append(" | ").append(decimal(row.means().get(key)));
            text.append(" |\n");
        }
        text.append("\n## Biggest single-step drops\n\n")
                .append("| Metric | From → to | Matched songs | Mean change |\n| --- | --- | ---: | ---: |\n");
        for (var drop : report.biggestDrops()) text.append("| ").append(drop.metric()).append(" | ")
                .append(label(drop.from())).append(" → ").append(label(drop.to())).append(" | ").append(drop.songs())
                .append(" | ").append(decimal(drop.delta())).append(" |\n");
        appendLosses(text, "R0 losses across all tonal notes", report.exactLosses());
        appendLosses(text, "R0 losses along the native lead's route to FM1", report.exactLeadLosses());
        text.append("\n## Per song\n\n");
        appendRows(text, report.songs(), false);
        text.append("\n## Credits torture cases\n\n");
        appendRows(text, report.torture(), true);
        for (var row : report.torture()) {
            if (row.trace() == null || row.trace().sections().isEmpty()) continue;
            text.append("\n### ").append(markdownText(songKey(row) + " " + row.songName()))
                    .append(" · ").append(label(row.rung())).append("\n\n")
                    .append("| Inferred section | Start (s) | End (s) | Note F1 | Melody F1 | Lead attack F1 |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (var section : row.trace().sections()) text.append("| ").append(markdownText(section.section())).append(" | ")
                    .append(decimal(section.start())).append(" | ").append(decimal(section.end())).append(" | ")
                    .append(decimal(section.noteF1())).append(" | ").append(decimal(section.melodyF1())).append(" | ")
                    .append(decimal(section.leadAttackF1())).append(" |\n");
        }
        return text.toString();
    }

    private static void appendLosses(StringBuilder text, String heading, List<StageLoss> rows) {
        text.append("\n## ").append(heading).append("\n\n");
        for (var row : rows) {
            text.append("- **").append(row.stage()).append("** lost ").append(row.attacks()).append(" attacks");
            if (!row.reasons().isEmpty()) {
                text.append(" (").append(String.join(", ", row.reasons().entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                        .map(reason -> markdownText(reason.getKey()) + " " + reason.getValue()).toList())).append(')');
            }
            text.append(".\n");
            for (var sample : row.examples()) text.append("  - ").append(sample.song()).append(' ')
                    .append(sample.note().lane()).append(", MIDI ").append(sample.note().pitch()).append(" at ")
                    .append(decimal(sample.note().second())).append(" s (").append(markdownText(sample.note().reason())).append(").\n");
        }
    }

    private static void appendRows(StringBuilder text, List<BenchLadderRun.Row> rows, boolean torture) {
        text.append("| Song | Rung | ").append(String.join(" | ", METRICS))
                .append(" | Transcribe | Roles | Allocation | Voicing/assemble |")
                .append(torture ? " Source s / end | Wall s | Sections / repeats / phrases | Project bytes |" : "")
                .append(" Result |\n| --- | --- |").append(" ---: |".repeat(METRICS.size() + 4))
                .append(torture ? " --- | ---: | --- | ---: |" : "").append(" --- |\n");
        for (var row : rows) {
            text.append("| ").append(markdownText(songKey(row) + " " + row.songName())).append(" | ").append(label(row.rung()));
            for (String key : METRICS) text.append(" | ").append(decimal(value(row, key)));
            for (var stage : List.of("transcribe", "roles", "allocation", "voicing/assemble")) {
                text.append(" | ").append(row.trace() == null ? "—" : row.trace().allNotes().lost().get(stage).count());
            }
            if (torture) {
                text.append(" | ").append(decimal(row.seconds())).append(" / ").append(row.nativeEnd())
                        .append(" | ").append(decimal(row.wallSeconds())).append(" | ");
                if (row.trace() == null) text.append("—");
                else text.append(row.trace().inferredSections()).append(" / ").append(row.trace().repeats())
                        .append(" / ").append(row.trace().phrases());
                text.append(" | ").append(row.projectBytes() == null ? "—" : row.projectBytes());
            }
            text.append(" | ").append(row.error() == null ? "OK" : markdownText(row.lastCompletedStage() + " · " + row.error()));
            if (row.listeningError() != null) text.append(" · ").append(markdownText(row.listeningError()));
            text.append(" |\n");
        }
    }

    private static void listening(List<Source> sources, Path target) throws IOException {
        try (var html = Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW)) {
            html.write("""
                    <!doctype html><html lang="en"><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Sonic reconstruction ladder listening tests</title>
                    <style>
                    body{font:1rem system-ui,sans-serif;max-width:62rem;margin:2rem auto;padding:0 1rem;background:#131923;color:#ecf0f6}
                    section{padding:1.3rem 0;border-bottom:1px solid #576275}
                    .takes{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:1rem}
                    audio{display:block;width:100%;margin:.5rem 0} small{color:#b0bdd0}
                    </style>
                    <h1>Sonic reconstruction ladder</h1>
                    """);
            var songs = new LinkedHashMap<String, List<Source>>();
            for (var source : sources) songs.computeIfAbsent(source.row().torture() + ":" + songKey(source.row()),
                    ignored -> new ArrayList<>()).add(source);
            for (var takes : songs.values()) {
                var row = takes.getFirst().row();
                html.write("<section><h2>" + escape(songKey(row) + " · " + row.songName()) + "</h2><small>"
                        + decimal(row.seconds()) + " s" + (row.torture() ? " · Credits torture case" : "")
                        + "</small><div class=\"takes\">");
                for (var take : takes) {
                    if (take.row().rung() == BenchLadder.Rung.EXACT) player(html, "Original", take.directory().resolve("original.mp3"));
                    String name = label(take.row().rung());
                    Path mp3 = take.directory().resolve("reconstruction.mp3");
                    if (Files.exists(mp3)) player(html, name, mp3);
                    if (take.row().error() != null)
                        html.write("<p>" + escape(name + " · " + take.row().error()) + "</p>");
                    if (take.row().listeningError() != null)
                        html.write("<p>" + escape(name + " · " + take.row().listeningError()) + "</p>");
                }
                if (!row.torture() && takes.stream().noneMatch(take -> take.row().rung() == BenchLadder.Rung.TRANSCRIBED))
                    html.write("<p>R3 transcribed was skipped.</p>");
                html.write("</div></section>\n");
            }
            html.write("</html>\n");
        }
    }

    private static void player(Writer html, String label, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        html.write("<div><strong>" + escape(label) + "</strong><audio controls preload=\"none\" src=\"data:audio/mpeg;base64,");
        html.write(Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        html.write("\"></audio></div>\n");
    }

    private static String songKey(BenchLadderRun.Row row) { return row.game() + "/%02x".formatted(row.song()); }
    private static String label(BenchLadder.Rung rung) { return rung.number + " " + rung.label; }
    private static String decimal(Double value) { return value == null ? "—" : String.format(Locale.ROOT, "%.3f", value); }
    private static String markdownText(String text) { return text.replace("|", "\\|").replaceAll("[\\r\\n]+", " "); }
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
