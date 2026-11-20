package com.opensmpsdeck.headless.bench;

import com.google.gson.JsonElement;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BenchReconstruction {
    record Midi(List<Part> parts, List<Note> notes) {}
    record Part(int id, boolean drums) {}
    record Note(int partId, int pitch, long startUs, long endUs) {}
    record Report(int presentations, long referenceUs, List<Match> matches, List<Change> pitchChanges,
                  List<Reduction> reductions, List<String> effectCopyLanes, TempoCurve tempoCurve) {}
    record TempoCurve(boolean warped) {}
    record Reduction(String change, String reason) {}
    record Change(int partId, int pitch, long startUs, long endUs) {}
    record Match(String kind, String lane, Key key, Choice choice) {}
    record Key(int partId, Long sectionStartUs) {}
    record LaneKind(String kind, String lane) {}
    record Choice(String assetId, List<Candidate> shortlist) {}
    record Candidate(String assetId) {}
    record Result(List<BenchMetrics.Event> events, Attribution attribution, Path audio, long referenceUs,
                  Map<String, Long> reductions, boolean warped) {}

    private static final long SECTION_US = 16_000_000L;
    private static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());

    private BenchReconstruction() {}

    static Result run(Path midi, Path audio, Path catalogue, Path output, boolean refine, Double seconds,
                      Map<Integer, Path> stems, BenchDrums drums, BenchCli.Hooks hooks,
                      BenchRolePlan.Mode rolePlan, List<BenchMetrics.Event> truthNotes, String steadyTempo) throws IOException {
        var args = new ArrayList<>(List.of("reconstruct", rolePlan == null ? "run" : "transcribe", midi.toString(), audio.toString(), catalogue.toString(),
                output.toString(), "--name", "Benchmark"));
        if (steadyTempo.equals("off")) {
            args.add("--steady-tempo");
            args.add("off");
        }
        if (refine) args.add("--audio-refine");
        if (seconds != null) {
            args.add("--seconds");
            args.add(seconds.toString());
        }
        stems.forEach((part, file) -> {
            args.add("--stem");
            args.add(part + "=" + file);
        });
        hooks.reconstruction().run(args.toArray(String[]::new), QUIET);
        if (rolePlan != null) {
            hooks.reconstruction().run(new String[]{"reconstruct", "structure", output.toString()}, QUIET);
            BenchRolePlan.write(output, truthNotes, rolePlan, hooks.reconstruction(), QUIET);
            hooks.reconstruction().run(new String[]{"reconstruct", "run", "--from", "roles", output.toString()}, QUIET);
        }
        var report = BenchFiles.read(output.resolve("report.json"), Report.class);
        var project = ProjectFiles.load(output.resolve("arrangement.smps.json")).project();
        var truth = BenchTimeline.read(ProjectCompiler.compileWithAssets(project), report.presentations(), hooks.sequencer());
        var events = excludeEffectCopies(BenchMetrics.events(truth, drums, false), report.effectCopyLanes());
        JsonElement source = hooks.midi().read(midi);
        var reductions = new java.util.TreeMap<String, Long>();
        if (report.reductions() != null) {
            for (var change : report.reductions())
                reductions.merge(change.reason() == null ? change.change() : change.reason(), 1L, Long::sum);
        }
        return new Result(events, new Attribution(BenchFiles.JSON.fromJson(source, Midi.class), report),
                output.resolve("render.wav"), report.referenceUs(), reductions, report.tempoCurve() != null && report.tempoCurve().warped());
    }

    static List<BenchMetrics.Event> excludeEffectCopies(List<BenchMetrics.Event> events, List<String> lanes) {
        if (lanes == null || lanes.isEmpty()) return events;
        return events.stream().filter(event -> !lanes.contains(event.lane())).toList();
    }

    static final class Attribution {
        private final Map<Integer, List<Note>> byPitch = new HashMap<>();
        private final Map<LaneKind, Map<Key, Choice>> choices = new HashMap<>();

        Attribution(Midi midi, Report report) {
            var drums = midi.parts.stream().filter(Part::drums).map(Part::id).collect(java.util.stream.Collectors.toSet());
            for (var note : midi.notes) {
                if (!drums.contains(note.partId) && note.startUs < report.referenceUs)
                    byPitch.computeIfAbsent(note.pitch, ignored -> new ArrayList<>()).add(note);
            }
            for (var change : report.pitchChanges) {
                var note = new Note(change.partId, change.pitch, change.startUs, change.endUs);
                byPitch.computeIfAbsent(note.pitch, ignored -> new ArrayList<>()).add(note);
            }
            for (var match : report.matches) {
                if (match.choice.assetId != null)
                    choices.computeIfAbsent(new LaneKind(match.kind, match.lane), ignored -> new HashMap<>()).put(match.key, match.choice);
            }
        }

        private Choice choice(Note note, String lane) {
            var fm = choices.getOrDefault(new LaneKind("FM_VOICE", lane),
                    choices.getOrDefault(new LaneKind("FM_VOICE", null), Map.of()));
            return fm.getOrDefault(new Key(note.partId, note.startUs / SECTION_US * SECTION_US), fm.get(new Key(note.partId, null)));
        }

        // The report does not carry note-to-part IDs. Attribute by source pitch,
        // nearest quantized onset, then part ID, and require the emitted FM hash.
        List<Note> candidates(BenchMetrics.Event note) {
            long frame = Math.round(note.start() * 60);
            return byPitch.getOrDefault(note.pitch(), List.of()).stream()
                    .filter(source -> Math.abs(frame - Math.round(source.startUs * 60.0 / 1_000_000)) <= 1)
                    .sorted(Comparator.comparingLong((Note source) -> Math.abs(Math.round(note.start() * 1_000_000) - source.startUs))
                            .thenComparingInt(Note::partId)).toList();
        }

        List<Candidate> shortlist(BenchMetrics.Event note) {
            if (note.hash() == null) return List.of();
            return candidates(note).stream().map(source -> choice(source, note.lane()))
                    .filter(choice -> choice != null && choice.assetId.equals("fm-voice-" + note.hash()))
                    .findFirst().map(Choice::shortlist).orElse(List.of());
        }

        boolean fromPart(BenchMetrics.Event note, int part) {
            var candidates = candidates(note);
            if (note.hash() != null) {
                var matchingVoice = candidates.stream().filter(source -> {
                    var choice = choice(source, note.lane());
                    return choice != null && choice.assetId.equals("fm-voice-" + note.hash());
                }).findFirst();
                if (matchingVoice.isPresent()) return matchingVoice.get().partId == part;
            }
            return !candidates.isEmpty() && candidates.getFirst().partId == part;
        }
    }
}
