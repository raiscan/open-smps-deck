package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionAttackGrid.STEPS;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

public final class ReconstructionDescribe {
    private static final String GRID = "16 steps/bar; . rest, - sustain, .N/-N runs, [A+B] chord; attack counts single/chord";
    private static final String[] PITCH = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final Map<Integer, String> DRUM = Map.ofEntries(Map.entry(35, "K35"), Map.entry(36, "K"),
            Map.entry(38, "S"), Map.entry(40, "S40"), Map.entry(42, "H"), Map.entry(44, "H44"), Map.entry(46, "O"),
            Map.entry(49, "C"), Map.entry(51, "R"));

    private record Part(int id, String name, int program, boolean drums, int notes) {}
    private record Line(int part, String range, double notesPerBar, int polyphony, int figureBars,
                        Map<Role, Integer> roles, List<String> bars) {}
    private record LineSummary(Line line, List<Integer> attacks) {}
    private record Grid(String pattern, int singles, int chords) {}
    private record Section(String id, int firstBar, int lastBar, double startSeconds, double endSeconds,
                           String sameAs, List<Line> parts, List<Integer> attacks) {
        static Section of(String id, int first, int last, double start, double end, String sameAs,
                          List<LineSummary> summaries) {
            return new Section(id, first, last, start, end, sameAs, summaries.stream().map(LineSummary::line).toList(),
                    summaries.stream().flatMap(summary -> summary.attacks.stream()).toList());
        }
    }
    private record View(double bpm, double barSeconds, String tempoSource, String grid,
                        ReconstructionTimeMap.TimeBase timeBase, List<Part> parts,
                        List<Section> sections, ReconstructionBeatMap.Summary tempoCurve,
                        ReconstructionLeadContinuity.Stats leadContinuity, List<Run> fastRuns,
                        ReconstructionChordReduction.Audit chordAudit,
                        List<Envelope> vibratoEnvelopes) {}
    private record Envelope(String id, int part, String lane, double startSeconds,
                            List<ReconstructionEditing.VibratoSegment> segments) {}
    private record Run(String kind, int part, double start, double end, Role role, double confidence,
                       List<ReconstructionFastRuns.Step> steps, List<ReconstructionFigureVoices.Voice> voices) {}

    private record Span(int first, int last) {}

    private ReconstructionDescribe() {}

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("reconstruct describe expects WORK_DIR");
        var seen = new HashSet<String>();
        boolean json = false;
        String section = null, parts = null, bars = null;
        Path output = null;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (!seen.add(flag)) throw new IllegalArgumentException("Duplicate option " + flag);
            if (flag.equals("--json")) {
                json = true;
                continue;
            }
            if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            String value = args[i];
            switch (flag) {
                case "--section" -> section = value;
                case "--parts" -> parts = value;
                case "--bars" -> bars = value;
                case "--out" -> output = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown describe option " + flag);
            }
        }
        String description = describe(Path.of(args[0]), json, section, parts, bars);
        if (output == null) out.print(description);
        else Files.writeString(output, description, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public static String describe(Path work, boolean json, String section, String parts, String bars) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var structure = ReconstructionStages.readStructure(work.resolve("structure.json"), score);
        var rolesFile = work.resolve("roles.json");
        Map<String, Role> roles = null;
        List<Note> effectiveNotes = structure.notes();
        ReconstructionLeadContinuity.Stats continuity = null;
        if (!Files.notExists(rolesFile, LinkOption.NOFOLLOW_LINKS)) {
            var current = ReconstructionArtifacts.read(rolesFile, ReconstructionRoles.Result.class);
            ReconstructionChecks.roles(current, score);
            roles = new HashMap<>();
            var stats = ReconstructionLeadContinuity.Stats.of(current.notes());
            if (!stats.isEmpty()) continuity = stats;
            effectiveNotes = describeNotes(structure.notes(), current.notes());
            for (var decision : current.notes()) roles.put(decision.note().id(), decision.role());
        }
        var filter = new HashSet<Integer>();
        if (parts != null) {
            var ids = score.source().parts().stream().map(MidiScore.Part::id).collect(java.util.stream.Collectors.toSet());
            for (String part : parts.split(",", -1)) {
                int id = Integer.parseInt(part.strip());
                if (!ids.contains(id)) throw new IllegalArgumentException("Unknown part " + id);
                filter.add(id);
            }
        }
        var span = bars == null ? new ReconstructionRolePlan.BarRange(1, structure.bars().size())
                : ReconstructionRolePlan.BarRange.parse(bars, structure.bars().size(), "--bars");
        if (section != null && structure.sections().stream().noneMatch(s -> s.id().equals(section)))
            throw new IllegalArgumentException("Unknown section " + section);
        var allocationFile = work.resolve("allocation.json");
        var audit = !Files.isRegularFile(allocationFile) ? null : ReconstructionChordReduction.audit(score,
                ReconstructionArtifacts.read(allocationFile, ReconstructionAllocation.Result.class),
                ReconstructionSections.load(work, score, structure), List.of());
        var envelopes = new ArrayList<Envelope>();
        if (ReconstructionEdits.read(work).accepted().intents().stream().anyMatch(edit -> edit.json().has("vibratoEnvelope"))) {
            for (var row : ReconstructionTrace.read(work, score).values()) {
                if (!filter.isEmpty() && !filter.contains(row.part())) continue;
                for (var gate : row.voiced()) {
                    if (gate.vibratoEnvelope() == null) continue;
                    envelopes.add(new Envelope(row.id(), row.part(), gate.lane(), gate.gate().projectStart(), gate.vibratoEnvelope()));
                }
            }
        }
        var view = build(score, structure, effectiveNotes, roles, section, filter, span,
                section == null && bars == null, continuity, audit, envelopes);
        return json ? new GsonBuilder().disableHtmlEscaping().create().toJson(view) + "\n" : text(view);
    }

    private static List<Note> describeNotes(List<Note> structural, List<ReconstructionRoles.Decision> decisions) {
        var notes = new LinkedHashMap<String, Note>();
        for (var note : structural) notes.put(note.id(), note);
        for (var decision : decisions) {
            var note = decision.note();
            var original = notes.get(note.id());
            if (original == null || decision.reason() != null
                    && !decision.reason().contains("sustained-inner-tone")) notes.put(note.id(), note);
            else if (note.motion() != null) notes.put(note.id(), original.withMotion(note.motion()));
        }
        return notes.values().stream().sorted(ReconstructionStages.NOTE_ORDER).toList();
    }

    private static View build(ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                              List<Note> effectiveNotes, Map<String, Role> roles, String onlySection, Set<Integer> filter,
                              ReconstructionRolePlan.BarRange range, boolean collapse,
                              ReconstructionLeadContinuity.Stats continuity, ReconstructionChordReduction.Audit audit, List<Envelope> envelopes) {
        var notes = new HashMap<Integer, List<Note>>();
        for (var note : effectiveNotes)
            notes.computeIfAbsent(note.part(), ignored -> new ArrayList<>()).add(note);
        var parts = score.source().parts().stream().filter(p -> filter.isEmpty() || filter.contains(p.id()))
                .map(p -> new Part(p.id(), p.name(), p.program(), p.drums(), notes.getOrDefault(p.id(), List.of()).size()))
                .toList();
        var timeline = ReconstructionTimeMap.of(score);
        var beatMap = !timeline.warped() && score.usesBeatGrid() ? score.beatMap() : null;
        var barNumbers = new HashMap<String, Integer>();
        var bars = structure.bars();
        for (int i = 0; i < bars.size(); i++) barNumbers.put(bars.get(i).id(), i + 1);
        var repeatKeys = new HashMap<String, String>();
        for (var repeat : structure.repeats())
            for (String id : repeat.bars()) repeatKeys.put(id, repeat.id());
        var phraseKeys = new HashMap<Span, Set<String>>();
        if (structure.phrases() != null) {
            for (var phrase : structure.phrases()) for (var instance : phrase.instances()) {
                var span = new Span(barNumbers.get(instance.bars().getFirst()), barNumbers.get(instance.bars().getLast()));
                phraseKeys.computeIfAbsent(span, ignored -> new HashSet<>()).add(phrase.id());
            }
        }
        var original = new ArrayList<Section>();
        var sections = new ArrayList<Section>();
        for (var section : structure.sections()) {
            if (onlySection != null && !onlySection.equals(section.id())) continue;
            int first = Math.max(range.first(), barNumbers.get(section.bars().getFirst()));
            int last = Math.min(range.last(), barNumbers.get(section.bars().getLast()));
            if (first > last) continue;
            var lines = new ArrayList<LineSummary>();
            for (var part : parts)
                lines.add(line(part, notes.getOrDefault(part.id, List.of()), bars.subList(first - 1, last), roles, beatMap));
            double start = bars.get(first - 1).start(), end = bars.get(last - 1).end();
            var current = Section.of(section.id(), first, last, start, end, null, lines);
            Section prior = collapse ? repeat(original, current, bars, repeatKeys, phraseKeys) : null;
            if (prior == null) {
                original.add(current);
                sections.add(current);
            } else {
                var differences = new ArrayList<LineSummary>();
                for (int i = 0; i < lines.size(); i++)
                    if (!lines.get(i).line.equals(prior.parts.get(i))) differences.add(lines.get(i));
                sections.add(Section.of(section.id(), first, last, start, end, prior.id, differences));
            }
        }
        var lengths = bars.stream().filter(ReconstructionStructure.Bar::complete)
                .mapToDouble(b -> b.end() - b.start()).sorted().toArray();
        var tempo = score.tempo();
        boolean inferred = tempo != null && !tempo.source().equals("midi");
        double barSeconds = timeline.warped() ? 60 * score.beatMap().beatsPerBar() / timeline.bpm()
                : inferred && beatMap == null ? tempo.selected().barLength()
                : lengths.length == 0 ? 2 : lengths[lengths.length / 2];
        double bpm = timeline.warped() ? timeline.bpm()
                : beatMap != null ? beatMap.openingBpm() : inferred ? tempo.selected().bpm()
                : 60 * (tempo == null ? 4 : tempo.selected().beatsPerBar()) / barSeconds;
        var figures = new LinkedHashMap<Note, String>();
        for (var note : effectiveNotes)
            if (note.run() != null) figures.put(note, note.id());
        if (score.retriggers() != null)
            for (var burst : score.retriggers()) figures.put(timeline.projectNote(burst.figure()), burst.anchor());
        var runs = figures.keySet().stream().filter(note -> (filter.isEmpty() || filter.contains(note.part()))
                        && sections.stream().anyMatch(section -> note.end() > section.startSeconds && note.start() < section.endSeconds))
                .sorted(ReconstructionStages.NOTE_ORDER)
                .map(note -> new Run(note.source(), note.part(), note.start(), note.end(),
                        roles == null ? null : roles.get(figures.get(note)), note.confidence(), note.run(),
                        score.figureVoices() == null ? null : score.figureVoices().get(note.id()))).toList();
        var visibleEnvelopes = envelopes.stream().filter(envelope -> sections.stream().anyMatch(s ->
                envelope.startSeconds() >= s.startSeconds && envelope.startSeconds() < s.endSeconds)).toList();
        return new View(round(bpm), round(barSeconds), inferred ? tempo.source() : "MIDI bar estimate", GRID, timeline.timeBase(), parts,
                List.copyOf(sections), score.usesBeatGrid() || timeline.warped() ? timeline.summary() : null, continuity, runs.isEmpty() ? null : runs, audit, visibleEnvelopes.isEmpty() ? null : visibleEnvelopes);
    }

    private static Section repeat(List<Section> earlier, Section current, List<ReconstructionStructure.Bar> bars,
                                  Map<String, String> repeatKeys, Map<Span, Set<String>> phraseKeys) {
        var currentPhrases = phraseKeys.getOrDefault(new Span(current.firstBar, current.lastBar), Set.of());
        for (var prior : earlier) {
            if (prior.lastBar - prior.firstBar != current.lastBar - current.firstBar) continue;
            var previousPhrases = phraseKeys.getOrDefault(new Span(prior.firstBar, prior.lastBar), Set.of());
            boolean matchingBars = true;
            for (int i = 0; i <= current.lastBar - current.firstBar; i++) {
                String key = repeatKeys.get(bars.get(current.firstBar - 1 + i).id());
                matchingBars &= key != null && key.equals(repeatKeys.get(bars.get(prior.firstBar - 1 + i).id()));
            }
            if (matchingBars || currentPhrases.stream().anyMatch(previousPhrases::contains)
                    || current.parts.equals(prior.parts)) return prior;
        }
        return null;
    }

    private static LineSummary line(Part part, List<Note> notes, List<ReconstructionStructure.Bar> bars,
                                    Map<String, Role> roles, ReconstructionBeatMap beatMap) {
        double start = bars.getFirst().start(), end = bars.getLast().end();
        var sounding = notes.stream().filter(n -> n.start() < end && (n.start() >= start || n.end() > start)).toList();
        var counts = roles == null ? null : new EnumMap<Role, Integer>(Role.class);
        var releases = new PriorityQueue<Double>();
        int min = 127, max = 0, polyphony = 0, attacks = 0;
        for (var note : sounding) {
            if (note.start() >= start) {
                attacks++;
                if (counts != null && roles.containsKey(note.id())) counts.merge(roles.get(note.id()), 1, Integer::sum);
            }
            double onset = Math.max(start, note.start());
            while (!releases.isEmpty() && releases.peek() <= onset) releases.remove();
            polyphony = Math.max(polyphony, releases.size() + 1);
            if (note.end() > onset) releases.add(note.end());
            min = Math.min(min, note.pitch());
            max = Math.max(max, note.pitch());
        }
        var grids = bars.stream().map(bar -> grid(bar, sounding, part.drums, beatMap)).toList();
        var patterns = grids.stream().map(Grid::pattern).toList();
        var textures = List.of(grids.stream().mapToInt(Grid::singles).sum(), grids.stream().mapToInt(Grid::chords).sum());
        String range = part.drums || sounding.isEmpty() ? null : pitch(min) + ".." + pitch(max);
        int figure = attacks == 0 ? 0 : figure(patterns);
        var line = new Line(part.id, range, round((double) attacks / bars.size()), polyphony, figure, counts, patterns);
        return new LineSummary(line, textures);
    }

    private static Grid grid(ReconstructionStructure.Bar bar, List<Note> notes, boolean drums, ReconstructionBeatMap beatMap) {
        var cells = new ArrayList<List<String>>(STEPS);
        var held = new boolean[STEPS];
        for (int i = 0; i < STEPS; i++) cells.add(new ArrayList<>());
        for (var note : notes) {
            if (note.start() >= bar.end()) break;
            if (note.start() < bar.start() && (drums || note.end() <= bar.start())) continue;
            int step = ReconstructionAttackGrid.attackStep(bar, note.start(), beatMap);
            if (note.start() >= bar.start())
                cells.get(step).add(drums ? DRUM.getOrDefault(note.pitch(), "D" + note.pitch()) : pitch(note.pitch())
                        + (note.motion() != null && note.motion().vibrato() >= ReconstructionLeadAudio.MODULATED ? "~" : "")
                        + (note.bend() == null ? "" : note.bend().marker()));
            if (!drums) {
                int release = Math.clamp(ReconstructionAttackGrid.roundedStep(bar, note.end(), beatMap), 0, STEPS);
                for (int i = note.start() >= bar.start() ? step + 1 : 0; i < release; i++) held[i] = true;
            }
        }
        var tokens = new ArrayList<String>();
        int singles = 0, chords = 0;
        for (int i = 0; i < STEPS;) {
            var cell = cells.get(i);
            if (!cell.isEmpty()) {
                tokens.add(cell.size() == 1 ? cell.getFirst() : "[" + String.join("+", cell) + "]");
                if (cell.size() == 1) singles++;
                else chords++;
                i++;
            } else {
                int next = i + 1;
                while (next < STEPS && cells.get(next).isEmpty() && held[next] == held[i]) next++;
                String token = held[i] ? "-" : ".";
                tokens.add(next == i + 1 ? token : token + (next - i));
                i = next;
            }
        }
        return new Grid(String.join(" ", tokens), singles, chords);
    }

    private static int figure(List<String> patterns) {
        for (int period = 1; period <= 2; period++) {
            for (int i = 0; i + 2 * period <= patterns.size(); i++) {
                var phrase = patterns.subList(i, i + period);
                if (phrase.stream().allMatch(p -> p.equals(".16") || p.equals("-16"))) continue;
                if (phrase.equals(patterns.subList(i + period, i + 2 * period))) return period;
            }
        }
        return 0;
    }

    static String pitch(int pitch) {
        return PITCH[pitch % 12] + (pitch / 12 - 1);
    }

    private static double round(double value) { return Math.round(value * 1000) / 1000.0; }

    private static String text(View view) {
        var out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%.3f BPM, %.3f s/bar (%s)%n%s%n",
                view.bpm, view.barSeconds, view.tempoSource, view.grid));
        out.append("Time base: ").append(view.timeBase).append(" seconds\n");
        if (view.tempoCurve != null) out.append(String.format(Locale.ROOT, "Source tempo %.3f–%.3f BPM%s%n",
                view.tempoCurve.startBpm(), view.tempoCurve.endBpm(), view.tempoCurve.warped()
                        ? String.format(Locale.ROOT, ", steady playback at %.3f BPM", view.tempoCurve.steadyBpm()) : ""));
        var continuity = view.leadContinuity;
        if (continuity != null) {
            out.append(String.format(Locale.ROOT, "Lead continuity: %d split, %d added, %d extended",
                    continuity.split(), continuity.added(), continuity.extended()));
            if (continuity.legato() != null) out.append(", ").append(continuity.legato()).append(" legato");
            out.append('\n');
        }
        if (view.sections.stream().flatMap(section -> section.parts.stream())
                .flatMap(part -> part.bars.stream()).anyMatch(pattern -> pattern.contains("~")))
            out.append("~ periodic pitch modulation\n");
        if (view.vibratoEnvelopes != null) for (var envelope : view.vibratoEnvelopes) {
            out.append("Vibrato envelope ").append(envelope.id()).append(" ").append(envelope.lane());
            for (var segment : envelope.segments()) out.append(String.format(Locale.ROOT,
                    "  +%dt %.2f Hz %.1f cents", segment.startOffset(), segment.rateHz(), segment.depthCents()));
            out.append('\n');
        }
        if (view.fastRuns != null) for (var run : view.fastRuns) {
            var sequence = run.steps.stream().map(step -> pitch(step.pitch())).collect(java.util.stream.Collectors.joining(" "));
            out.append(String.format(Locale.ROOT, "%s P%d %.3f–%.3f s %s (%.2f)%n",
                    run.kind, run.part, run.start, run.end, sequence, run.confidence));
            if (run.voices != null) for (var voice : run.voices) {
                var pitches = voice.pitches().stream().map(ReconstructionDescribe::pitch)
                        .collect(java.util.stream.Collectors.joining(" "));
                out.append("  ").append(voice.role()).append(" evidence ").append(pitches).append('\n');
            }
        }
        if (view.chordAudit != null) {
            out.append("Unallocated tonal notes (whole song) ").append(view.chordAudit.song()).append('\n');
            view.chordAudit.sections().forEach((id, section) -> out.append("  ").append(id).append(' ')
                    .append(section.label().replaceAll("\\p{Cntrl}", " ")).append(' ').append(section.counts()).append('\n'));
        }
        for (var part : view.parts)
            out.append(String.format(Locale.ROOT, "P%d %s | program %d | drums %s | %d notes%n",
                    part.id, part.name.replaceAll("\\p{Cntrl}", " "), part.program, part.drums, part.notes));
        for (var section : view.sections) {
            out.append(String.format(Locale.ROOT, "%n%s bars %d-%d", section.id, section.firstBar, section.lastBar));
            if (view.timeBase == ReconstructionTimeMap.TimeBase.project)
                out.append(String.format(Locale.ROOT, " (%.3f–%.3f s)", section.startSeconds, section.endSeconds));
            if (section.sameAs != null) out.append(" same as ").append(section.sameAs);
            out.append('\n');
            for (int index = 0; index < section.parts.size(); index++) {
                var part = section.parts.get(index);
                out.append(String.format(Locale.ROOT, "P%d %s %.2f notes/bar poly %d figure %s",
                        part.part, part.range == null ? "" : part.range, part.notesPerBar, part.polyphony,
                        part.figureBars == 0 ? "-" : part.figureBars + "b"));
                out.append(' ').append(section.attacks.get(2 * index)).append('/').append(section.attacks.get(2 * index + 1));
                if (part.roles != null) part.roles.forEach((role, count) ->
                        out.append(' ').append(role).append('=').append(count));
                out.append('\n');
                for (int i = 0; i < part.bars.size(); i++)
                    out.append(section.firstBar + i).append(' ').append(part.bars.get(i)).append('\n');
            }
        }
        return out.toString();
    }
}
