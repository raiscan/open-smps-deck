package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.NOTE_ORDER;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;

final class ReconstructionRoles {
    static final double DEFAULT_HARMONY_HZ = 7.5;
    private static final double RELEASE_OVERLAP = .035;
    private static final double CONTOUR_CONTEXT = 1.25;

    enum Role { melody, counter, bass, harmony, drums, drop }

    record Decision(Note note, Role role, @ReconstructionStages.Nullable String reason,
                    @ReconstructionStages.Nullable String mergedInto, @ReconstructionStages.Nullable Double preLegatoEnd,
                    @ReconstructionStages.Nullable String assignmentReason) {
        Decision(Note note, Role role, String reason, String mergedInto, Double preLegatoEnd) {
            this(note, role, reason, mergedInto, preLegatoEnd, null);
        }
        Decision(Note note, Role role, String reason, String mergedInto) {
            this(note, role, reason, mergedInto, null);
        }
    }
    record Result(int version, double harmonyMaxHz, List<Decision> notes,
                  @ReconstructionStages.Nullable ReconstructionTimeMap.TimeBase timeBase,
                  @ReconstructionStages.Nullable Map<String, String> structureNoteHashes,
                  @ReconstructionStages.Nullable Map<String, ReconstructionEdits.Pin> lanePins) {
        Result(int version, double harmonyMaxHz, List<Decision> notes, ReconstructionTimeMap.TimeBase timeBase,
               Map<String, String> structureNoteHashes) {
            this(version, harmonyMaxHz, notes, timeBase, structureNoteHashes, null);
        }
        Result(int version, double harmonyMaxHz, List<Decision> notes, ReconstructionTimeMap.TimeBase timeBase) {
            this(version, harmonyMaxHz, notes, timeBase, null);
        }
    }
    private record Scope(int index, ReconstructionRolePlan.Rule rule, ReconstructionRolePlan.BarRange bars) {
        boolean applies(String section, int bar) {
            return (bars == null || bar >= bars.first() && bar <= bars.last())
                    && (rule.sections() == null || rule.sections().contains("*") || rule.sections().contains(section));
        }

        String reason() { return "roleplan#" + index; }
    }

    private record PartPitch(int part, int pitch) {}

    private ReconstructionRoles() {}

    static Result assign(ReconstructionStructure.Result structure, List<MidiScore.Part> parts, double harmonyHz) {
        return assign(structure, parts, harmonyHz, null, null);
    }

    static Result assign(ReconstructionStructure.Result structure, List<MidiScore.Part> parts, double harmonyHz,
                         ReconstructionRolePlan plan, ReconstructionBeatMap beatMap) {
        return assign(structure, parts, harmonyHz, plan, beatMap, null);
    }

    static Result assign(ReconstructionStructure.Result structure, List<MidiScore.Part> parts, double harmonyHz,
                         ReconstructionRolePlan plan, ReconstructionBeatMap beatMap,
                         ReconstructionLeadContinuity continuity) {
        return assign(structure, parts, harmonyHz, plan, beatMap, continuity, null);
    }

    static Result assign(ReconstructionStructure.Result structure, List<MidiScore.Part> parts, double harmonyHz,
                         ReconstructionRolePlan plan, ReconstructionBeatMap beatMap,
                         ReconstructionLeadContinuity continuity, Integer leadPart) {
        return assign(structure, parts, harmonyHz, plan, beatMap, continuity, leadPart, Set.of(), Set.of(), note -> false, Set.of());
    }

    static Result assign(ReconstructionStructure.Result structure, List<MidiScore.Part> parts, double harmonyHz,
                         ReconstructionRolePlan plan, ReconstructionBeatMap beatMap,
                         ReconstructionLeadContinuity continuity, Integer leadPart, Set<String> fixedGates,
                         Set<String> separate,
                         java.util.function.Predicate<Note> suppressed, Set<Integer> addedParts) {
        var evidence = continuity == null ? Map.<String, ReconstructionLeadAudio.Evidence>of() : continuity.evidence();
        var accents = continuity == null ? Map.<String, Double>of() : continuity.accents();
        var programs = new HashMap<Integer, Integer>();
        var roles = new LinkedHashMap<String, Role>();
        var tones = new ArrayList<Note>();
        var drums = new HashSet<Integer>();
        for (var part : parts) {
            if (part.drums()) drums.add(part.id());
            programs.put(part.id(), part.program());
        }
        for (var note : structure.notes()) {
            roles.put(note.id(), drums.contains(note.part()) ? Role.drums : Role.harmony);
            if (!drums.contains(note.part())) tones.add(note);
        }
        var layout = structure.roleLayout() == null
                ? new ReconstructionStructure.Layout(structure.bars(), structure.sections(), structure.repeats())
                : structure.roleLayout();
        var contourTones = tones.stream().filter(note -> !ReconstructionStemTemplates.SOURCE.equals(note.source())).toList();
        var byBar = byBar(layout.bars(), contourTones);
        // Unmapped stems supplement the written arrangement. A dense decoded
        // chord should not replace an existing line on the reserved chip lane.
        boolean hasWrittenTones = !addedParts.isEmpty() && tones.stream()
                .anyMatch(note -> !addedParts.contains(note.part()));
        java.util.function.Predicate<Note> writtenTone = note -> !hasWrittenTones || !addedParts.contains(note.part());
        var leadTones = hasWrittenTones && leadPart == null
                ? contourTones.stream().filter(writtenTone).toList() : contourTones;
        var melody = melody(layout, leadTones, programs, evidence, accents, leadPart);
        for (String id : melody.lead()) roles.put(id, Role.melody);
        if (leadPart != null) for (var note : tones) {
            if (note.part() == leadPart && note.run() != null) roles.put(note.id(), Role.melody);
        }
        var phraseReasons = new HashMap<String, String>();
        if (continuity != null) continuity.restoreArticulatedPhrases(leadPart, tones, roles, phraseReasons);
        for (var section : layout.sections()) {
            var notes = section.bars().stream().flatMap(id -> byBar.get(id).stream()).toList();
            labelLine(hasWrittenTones ? notes.stream().filter(writtenTone).toList() : notes, roles, programs, Role.bass);
            if (notes.stream().anyMatch(n -> roles.get(n.id()) == Role.bass)) {
                restoreRegularLine(notes, roles, phraseReasons, Role.bass);
                labelLine(notes, roles, programs, Role.counter);
                restoreRegularLine(notes, roles, phraseReasons, Role.counter);
            }
        }
        Map<String, String> reasons = phraseReasons;
        if (plan != null) reasons.putAll(applyPlan(structure, roles, programs, plan, beatMap, evidence, accents));
        preferBendAttack(tones, roles, reasons);
        var octaveDrops = new HashSet<String>();
        if (harmonyHz > 0) for (String id : melody.octaveDoubles()) {
            if (roles.get(id) != Role.harmony || separate.contains(id)) continue;
            roles.put(id, Role.drop);
            octaveDrops.add(id);
        }
        // Surrounding tracks can make an independent line sound like an inner chord tone.
        // Only apply fragment thinning where the source part itself carries overlapping voices.
        var chordParts = new HashSet<Integer>();
        if (harmonyHz > 0) {
            contourTones.stream().collect(Collectors.groupingBy(Note::part)).forEach((part, notes) -> {
                if (!ReconstructionMelody.isMonophonic(notes)) chordParts.add(part);
            });
        }
        var decisions = thin(structure.notes(), roles, harmonyHz, reasons, octaveDrops, chordParts, separate);
        var scopes = plan == null ? List.<Scope>of() : scopes(structure, plan);
        java.util.function.BiPredicate<Decision, Note> permitted = (anchor, note) -> !suppressed.test(note)
                && !ReconstructionStemTemplates.SOURCE.equals(note.source())
                && (writtenTone.test(note) || anchor.note().part() == note.part())
                && (!fixedGates.contains(anchor.note().id()) || !anchor.note().id().equals(note.id()))
                && (scopes.isEmpty() || permitsContinuation(structure, scopes, anchor, note));
        if (continuity != null) decisions = continuity.repair(decisions, permitted, fixedGates);
        decisions = ReconstructionMelodyGapFill.promote(decisions, permitted);
        if (continuity != null) decisions = continuity.legato(decisions, permitted);
        var explained = decisions.stream().map(decision -> decision.reason() != null ? decision :
                new Decision(decision.note(), decision.role(), null, decision.mergedInto(), decision.preLegatoEnd(),
                        assignmentReason(decision.role(), leadPart != null && leadPart == decision.note().part()))).toList();
        return new Result(VERSION, harmonyHz, explained, structure.timeBase(),
                ReconstructionArtifacts.noteHashes(structure.notes()));
    }

    static String assignmentReason(Role role, boolean explicitLead) {
        return switch (role) {
            case melody -> explicitLead ? "explicit-lead-part" : "melody-contour";
            case bass -> "section-bass-line";
            case counter -> "section-counter-line";
            case harmony -> "remaining-tonal-note";
            case drums -> "midi-drum-part";
            case drop -> "role-drop";
        };
    }

    /** A transcriber can write the landing pitch as a simultaneous attack. */
    private static void preferBendAttack(List<Note> tones, Map<String, Role> roles, Map<String, String> reasons) {
        var byAttack = new HashMap<PartPitch, List<Note>>();
        for (var note : tones) {
            if (roles.get(note.id()) == Role.melody)
                byAttack.computeIfAbsent(new PartPitch(note.part(), note.pitch()), ignored -> new ArrayList<>()).add(note);
        }
        for (var note : tones) {
            var bend = note.bend();
            if (bend == null || bend.confidence() < .92 || bend.delay() < .040 || roles.get(note.id()) == Role.melody)
                continue;
            int cents = bend.points().getLast().cents();
            if (Math.abs(cents) < 150 || Math.abs(cents) > 400) continue;
            int terminalPitch = note.pitch() + (int) Math.round(cents / 100.0);
            if (Math.abs(cents - 100 * (terminalPitch - note.pitch())) > 30) continue;
            for (var landing : byAttack.getOrDefault(new PartPitch(note.part(), terminalPitch), List.of())) {
                if (Math.abs(landing.start() - note.start()) <= .010 && Math.abs(landing.end() - note.end()) <= .025
                        && landing.bend() == null && landing.run() == null && roles.get(landing.id()) == Role.melody) {
                    roles.put(note.id(), Role.melody);
                    roles.put(landing.id(), Role.harmony);
                    reasons.put(note.id(), ReconstructionBends.REASON);
                    break;
                }
            }
        }
    }

    private static List<Scope> scopes(ReconstructionStructure.Result structure, ReconstructionRolePlan plan) {
        var result = new ArrayList<Scope>();
        for (int i = 0; i < plan.rules().size(); i++) {
            var rule = plan.rules().get(i);
            var bars = rule.bars() == null ? null
                    : ReconstructionRolePlan.BarRange.parse(rule.bars(), structure.bars().size(), "roleplan.json");
            result.add(new Scope(i, rule, bars));
        }
        return result;
    }

    private static boolean permitsContinuation(ReconstructionStructure.Result structure, List<Scope> scopes,
                                                Decision anchor, Note note) {
        boolean extension = note.id().equals(anchor.note().id());
        Scope owner = scopes.stream().filter(scope -> scope.reason().equals(anchor.reason())
                && scope.rule.role() == Role.melody).findFirst().orElse(null);
        boolean chord = extension && owner != null && "chord".equals(owner.rule.texture());
        double start = extension ? anchor.note().end() : note.start();
        return permitsAt(structure, scopes, owner, note, start, chord)
                && permitsAt(structure, scopes, owner, note, Math.nextDown(note.end()), chord);
    }

    private static boolean permitsAt(ReconstructionStructure.Result structure, List<Scope> scopes, Scope owner,
                                     Note note, double time, boolean chord) {
        int bar = 0;
        for (var section : structure.sections()) for (String ignored : section.bars()) {
            if (structure.bars().get(bar++).end() <= time) continue;
            var at = note.withTiming(time, note.end());
            for (var scope : scopes.reversed()) {
                if (scope.applies(section.id(), bar) && scope.rule.includes(at, chord))
                    return scope.rule.role() == Role.melody;
            }
            // A time-, pitch- or section-bounded planned line must keep its scope.
            if (owner != null) return owner.applies(section.id(), bar) && owner.rule.includes(at, chord);
            return melodyScope(scopes, section.id(), bar, time) == null;
        }
        return false;
    }

    private static Map<String, String> applyPlan(ReconstructionStructure.Result structure, Map<String, Role> roles,
                                                  Map<Integer, Integer> programs, ReconstructionRolePlan plan,
                                                  ReconstructionBeatMap beatMap,
                                                  Map<String, ReconstructionLeadAudio.Evidence> evidence,
                                                  Map<String, Double> accents) {
        var scopes = scopes(structure, plan);
        var byPart = new HashMap<Integer, List<Scope>>();
        for (var scope : scopes)
            byPart.computeIfAbsent(scope.rule.part(), ignored -> new ArrayList<>()).add(scope);
        var byBar = byBar(structure.bars(), structure.notes());
        var chords = plan.rules().stream().anyMatch(ReconstructionRolePlan.Rule::filtersTexture)
                ? ReconstructionAttackGrid.chordNotes(structure.bars(), byBar, beatMap) : Set.<String>of();
        var reasons = new HashMap<String, String>();
        int barNumber = 0;
        for (var section : structure.sections()) {
            var candidates = new LinkedHashMap<Scope, List<Note>>();
            for (String bar : section.bars()) {
                barNumber++;
                var notes = byBar.get(bar);
                var plannedLines = new EnumMap<Role, Set<Scope>>(Role.class);
                for (var note : notes) {
                    for (var scope : byPart.getOrDefault(note.part(), List.of()).reversed()) {
                        if (!scope.applies(section.id(), barNumber) || !scope.rule.includes(note, chords.contains(note.id())))
                            continue;
                        reasons.put(note.id(), scope.reason());
                        Role role = scope.rule.role();
                        if (ReconstructionRolePlan.traced(role) && role != Role.melody)
                            plannedLines.computeIfAbsent(role, ignored -> new HashSet<>()).add(scope);
                        if (scope.rule.line() == ReconstructionRolePlan.Voice.all) {
                            roles.put(note.id(), role);
                        } else {
                            roles.put(note.id(), Role.harmony);
                            candidates.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(note);
                        }
                        break;
                    }
                }
                for (var note : notes) {
                    if (reasons.containsKey(note.id())) continue;
                    Role role = roles.get(note.id());
                    Scope winner = role == Role.melody ? melodyScope(scopes, section.id(), barNumber, note.start()) : null;
                    for (var scope : plannedLines.getOrDefault(role, Set.of()))
                        if (scope.rule.includesTime(note.start()) && (winner == null || scope.index > winner.index))
                            winner = scope;
                    if (winner != null) {
                        roles.put(note.id(), Role.harmony);
                        reasons.put(note.id(), winner.reason());
                    }
                }
            }
            candidates.forEach((scope, notes) -> {
                boolean low = scope.rule.line() == ReconstructionRolePlan.Voice.bottom;
                if (scope.rule.role() == Role.melody) {
                    for (var note : ReconstructionMelody.track(notes, low, evidence, accents)) roles.put(note.id(), Role.melody);
                } else {
                    traceLine(notes, roles, programs, scope.rule.role(), low);
                }
            });
        }
        return reasons;
    }

    private static Scope melodyScope(List<Scope> scopes, String section, int bar, double time) {
        for (var scope : scopes.reversed()) {
            if (scope.rule.role() == Role.melody && scope.applies(section, bar) && scope.rule.includesTime(time))
                return scope;
        }
        return null;
    }

    static ReconstructionMelody.Selection melody(ReconstructionStructure.Layout layout, List<Note> tones,
                                                  Map<Integer, Integer> programs) {
        return melody(layout, tones, programs, Map.of(), Map.of(), null);
    }

    private static ReconstructionMelody.Selection melody(ReconstructionStructure.Layout layout, List<Note> tones,
                                                          Map<Integer, Integer> programs,
                                                          Map<String, ReconstructionLeadAudio.Evidence> evidence,
                                                          Map<String, Double> accents, Integer leadPart) {
        var melody = ReconstructionMelody.extract(layout, tones, programs, evidence, accents, leadPart);
        if (leadPart != null) return melody;
        var roles = new HashMap<String, Role>();
        for (var note : tones) roles.put(note.id(), melody.lead().contains(note.id()) ? Role.melody : Role.harmony);
        alignRepeatedMelody(layout, byBar(layout.bars(), tones), roles);
        var lead = new HashSet<String>();
        roles.forEach((id, role) -> {
            if (role == Role.melody) lead.add(id);
        });
        return new ReconstructionMelody.Selection(lead, melody.octaveDoubles());
    }

    static Map<String, List<Note>> byBar(List<ReconstructionStructure.Bar> bars, List<Note> sorted) {
        var byBar = new LinkedHashMap<String, List<Note>>();
        int index = 0;
        for (var bar : bars) {
            var notes = new ArrayList<Note>();
            while (index < sorted.size() && sorted.get(index).start() < bar.end()) notes.add(sorted.get(index++));
            byBar.put(bar.id(), notes);
        }
        return byBar;
    }

    private static void alignRepeatedMelody(ReconstructionStructure.Layout structure, Map<String, List<Note>> byBar,
                                            Map<String, Role> roles) {
        var bars = new HashMap<String, ReconstructionStructure.Bar>();
        for (var bar : structure.bars()) bars.put(bar.id(), bar);
        for (var group : structure.repeats()) {
            String template = group.bars().stream().max(Comparator.comparingDouble(id -> byBar.get(id).stream()
                    .filter(note -> roles.get(note.id()) == Role.melody)
                    .mapToDouble(note -> Math.min(.35, note.end() - note.start()) * note.confidence()).sum())).orElseThrow();
            var melody = byBar.get(template).stream().filter(note -> roles.get(note.id()) == Role.melody).toList();
            if (melody.isEmpty()) continue;
            for (var instance : group.bars()) {
                if (instance.equals(template)) continue;
                var matches = new HashSet<String>();
                var notes = byBar.get(instance);
                for (var note : melody) {
                    double onset = bars.get(template).fraction(note.start());
                    Note match = ReconstructionStructure.nearest(bars.get(instance), notes, note, onset, matches);
                    if (match != null) matches.add(match.id());
                }
                for (var note : notes) {
                    if (matches.contains(note.id())) roles.put(note.id(), Role.melody);
                    else if (roles.get(note.id()) == Role.melody) roles.put(note.id(), Role.harmony);
                }
            }
        }
    }

    /**
     * A pitch-turn penalty can make contour tracing jump past a pulse. Fill
     * holes in an established line before a second role can claim the pulse.
     */
    private static void restoreRegularLine(List<Note> section, Map<String, Role> roles,
                                           Map<String, String> reasons, Role role) {
        var parts = section.stream().collect(Collectors.groupingBy(Note::part));
        var established = section.stream().filter(note -> roles.get(note.id()) == role).toList();
        var restored = new ArrayList<Note>();
        for (var part : parts.values()) {
            for (int i = 2; i + 2 < part.size(); i++) {
                if (roles.get(part.get(i).id()) != Role.harmony || roles.get(part.get(i - 1).id()) != role
                        || roles.get(part.get(i - 2).id()) != role) continue;
                int next = i + 1;
                if (roles.get(part.get(next).id()) == Role.harmony) next++;
                if (next + 1 >= part.size() || roles.get(part.get(next).id()) != role
                        || roles.get(part.get(next + 1).id()) != role) continue;
                var window = part.subList(i - 2, next + 2);
                if (!regularMonophonic(window, i > 2 ? part.get(i - 3) : null)) continue;
                var first = part.get(i);
                var last = part.get(next - 1);
                if (established.stream().anyMatch(note -> note.part() != first.part()
                        && note.start() < last.end() && note.end() > first.start())) continue;
                restored.addAll(part.subList(i, next));
            }
        }
        for (var note : restored) {
            roles.put(note.id(), role);
            reasons.put(note.id(), "regular-monophonic-line");
        }
    }

    private static boolean regularMonophonic(List<Note> window, Note predecessor) {
        double period = (window.getLast().start() - window.getFirst().start()) / (window.size() - 1);
        if (period < .025 || period > .160 || predecessor != null
                && predecessor.end() > window.getFirst().start() + .002) return false;
        for (int i = 1; i < window.size(); i++) {
            var prior = window.get(i - 1);
            var next = window.get(i);
            if (Math.abs(next.start() - prior.start() - period) > .20 * period
                    || prior.end() > next.start() + .002 || next.start() - prior.end() > .45 * period) return false;
        }
        return true;
    }

    private static void labelLine(List<Note> section, Map<String, Role> roles, Map<Integer, Integer> programs, Role role) {
        List<Note> notes = section.stream().filter(note -> roles.get(note.id()) == Role.harmony).toList();
        traceLine(notes, roles, programs, role, role == Role.bass);
    }

    private static void traceLine(List<Note> notes, Map<String, Role> roles, Map<Integer, Integer> programs,
                                  Role role, boolean low) {
        if (notes.isEmpty()) return;
        int[] previous = new int[notes.size()];
        double[] path = new double[notes.size()];
        int best = 0;
        int bestDistant = -1;
        int distant = 0;
        for (int i = 0; i < notes.size(); i++) {
            Note current = notes.get(i);
            while (distant < i && notes.get(distant).start() < current.start() - CONTOUR_CONTEXT) {
                if (bestDistant < 0 || path[distant] > path[bestDistant]) bestDistant = distant;
                distant++;
            }
            double weight = salience(current, low, programs.get(current.part()), notes, i);
            path[i] = weight;
            previous[i] = -1;
            if (bestDistant >= 0 && notes.get(bestDistant).end() <= current.start() && path[bestDistant] > .18) {
                path[i] = path[bestDistant] + weight - .18;
                previous[i] = bestDistant;
            }
            for (int j = distant; j < i; j++) {
                Note prior = notes.get(j);
                double gap = current.start() - prior.end();
                if (current.start() - prior.start() < .025 || gap < -RELEASE_OVERLAP
                        || -gap > (prior.end() - prior.start()) / 3) continue;
                double cost = transition(prior, current, gap);
                double score = path[j] + weight - cost;
                if (score > path[i]) {
                    previous[i] = j;
                    path[i] = score;
                }
            }
            if (path[i] > path[best]) best = i;
        }
        for (int i = best; i >= 0; i = previous[i]) roles.put(notes.get(i).id(), role);
    }

    private static double salience(Note note, boolean low, int program, List<Note> notes, int index) {
        double duration = note.end() - note.start();
        double upper = note.pitch(), lower = note.pitch();
        for (int i = index; i >= 0 && notes.get(i).start() >= note.start() - .40; i--) {
            Note neighbor = notes.get(i);
            if (!salientNeighbor(note, neighbor)) continue;
            upper = Math.max(upper, neighbor.pitch());
            lower = Math.min(lower, neighbor.pitch());
        }
        for (int i = index + 1; i < notes.size() && notes.get(i).start() <= note.start() + .18; i++) {
            Note neighbor = notes.get(i);
            if (!salientNeighbor(note, neighbor)) continue;
            upper = Math.max(upper, neighbor.pitch());
            lower = Math.min(lower, neighbor.pitch());
        }
        double distance = low ? note.pitch() - lower : upper - note.pitch();
        double register = Math.max(-.6, .85 - distance / 13);
        if (program >= 32 && program <= 39) register += low ? .5 : -.7;
        double accent = (note.velocity() - 85) / 135.0;
        double sustain = Math.min(.34, duration) + .22 * Math.min(.65, duration);
        return (.036 + sustain) * (.73 + register + accent) * (.70 + .30 * note.confidence());
    }

    private static boolean salientNeighbor(Note note, Note neighbor) {
        return neighbor.end() >= note.start() - .10
                && (neighbor.end() - neighbor.start() >= .075 || Math.abs(note.start() - neighbor.start()) < .025);
    }

    static double transition(Note prior, Note current, double gap) {
        int leap = Math.abs(prior.exitPitch() - current.pitch());
        double continuity = Math.max(0, 1 - Math.max(0, gap) / .40);
        return continuity * (.011 * leap + .021 * Math.max(0, leap - 5)
                + (prior.part() == current.part() ? 0 : .105)) + Math.min(.18, Math.abs(gap) * .20);
    }

    private static List<Decision> thin(List<Note> notes, Map<String, Role> roles, double hz,
                                       Map<String, String> reasons, Set<String> octaveDrops, Set<Integer> chordParts,
                                       Set<String> separate) {
        var decisions = new LinkedHashMap<String, Decision>();
        var latest = new HashMap<PartPitch, Note>();
        var chain = new HashMap<String, String>();
        var sounding = new ArrayList<Note>();
        int future = 0;
        for (var note : notes) {
            Role role = roles.get(note.id());
            decisions.put(note.id(), new Decision(note, role, reason(reasons, note.id(),
                    octaveDrops.contains(note.id()) ? "isolated-harmony-octave-double"
                            : note.run() == null ? null : note.source()), null));
            if (note.run() != null || ReconstructionStemTemplates.SOURCE.equals(note.source()) || role == Role.drums || role == Role.drop || chordParts.isEmpty()) continue;
            while (future < notes.size() && notes.get(future).start() <= note.start() + .020) {
                Note incoming = notes.get(future++);
                if (roles.get(incoming.id()) != Role.drums && roles.get(incoming.id()) != Role.drop) sounding.add(incoming);
            }
            sounding.removeIf(n -> n.end() < note.start() - .020);
            var key = new PartPitch(note.part(), note.pitch());
            var previous = latest.put(key, note);
            if (role != Role.harmony || !chordParts.contains(note.part())
                    || previous == null || roles.get(previous.id()) != Role.harmony
                    || separate.contains(note.id()) || separate.contains(previous.id())) continue;
            double delta = note.start() - previous.start();
            if (delta < .030 || delta >= 1 / hz || Math.abs(note.start() - previous.end()) > .035
                    || Math.abs(note.velocity() - previous.velocity()) > 14) continue;
            if (sounding.stream().noneMatch(n -> n.pitch() > note.pitch())
                    || sounding.stream().noneMatch(n -> n.pitch() < note.pitch())) continue;
            String keptId = chain.getOrDefault(previous.id(), previous.id());
            Note kept = decisions.get(keptId).note();
            Note extended = new Note(kept.id(), kept.part(), kept.start(), Math.max(kept.end(), note.end()),
                    kept.pitch(), kept.velocity(), kept.source(), kept.confidence());
            decisions.put(keptId, new Decision(extended, Role.harmony, reason(reasons, keptId, "sustained-inner-tone"), null));
            decisions.put(note.id(), new Decision(note, Role.drop, reason(reasons, note.id(), "rapid-inner-tone-reattack"), keptId));
            chain.put(note.id(), keptId);
        }
        return decisions.values().stream().sorted(Comparator.comparing(Decision::note, NOTE_ORDER)).toList();
    }

    private static String reason(Map<String, String> reasons, String id, String detail) {
        String rule = reasons.get(id);
        if (rule == null) return detail;
        return detail == null ? rule : rule + " " + detail;
    }
}
