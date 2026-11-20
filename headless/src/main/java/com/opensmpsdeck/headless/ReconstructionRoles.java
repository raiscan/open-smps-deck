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

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.NOTE_ORDER;
import static com.opensmpsdeck.headless.ReconstructionStages.VERSION;

final class ReconstructionRoles {
    static final double DEFAULT_HARMONY_HZ = 7.5;
    private static final double RELEASE_OVERLAP = .035;
    private static final double CONTOUR_CONTEXT = 1.25;

    enum Role { melody, counter, bass, harmony, drums, drop }

    record Decision(Note note, Role role, @ReconstructionStages.Nullable String reason, @ReconstructionStages.Nullable String mergedInto) {}
    record Result(int version, double harmonyMaxHz, List<Decision> notes) {}
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
        var byBar = byBar(layout.bars(), tones);
        var melody = melody(layout, tones, programs);
        for (String id : melody.lead()) roles.put(id, Role.melody);
        for (var section : layout.sections()) {
            var notes = section.bars().stream().flatMap(id -> byBar.get(id).stream()).toList();
            labelLine(notes, roles, programs, Role.bass);
            if (notes.stream().anyMatch(n -> roles.get(n.id()) == Role.bass))
                labelLine(notes, roles, programs, Role.counter);
        }
        Map<String, String> reasons = plan == null ? Map.of() : applyPlan(structure, roles, programs, plan, beatMap);
        var octaveDrops = new HashSet<String>();
        if (harmonyHz > 0) for (String id : melody.octaveDoubles()) {
            if (roles.get(id) != Role.harmony) continue;
            roles.put(id, Role.drop);
            octaveDrops.add(id);
        }
        return new Result(VERSION, harmonyHz, thin(structure.notes(), roles, harmonyHz, reasons, octaveDrops));
    }

    private static Map<String, String> applyPlan(ReconstructionStructure.Result structure, Map<String, Role> roles,
                                                  Map<Integer, Integer> programs, ReconstructionRolePlan plan,
                                                  ReconstructionBeatMap beatMap) {
        var byPart = new HashMap<Integer, List<Scope>>();
        for (int i = 0; i < plan.rules().size(); i++) {
            var rule = plan.rules().get(i);
            var bars = rule.bars() == null ? null
                    : ReconstructionRolePlan.BarRange.parse(rule.bars(), structure.bars().size(), "roleplan.json");
            byPart.computeIfAbsent(rule.part(), ignored -> new ArrayList<>()).add(new Scope(i, rule, bars));
        }
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
                        if (ReconstructionRolePlan.traced(role))
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
                    var active = plannedLines.get(roles.get(note.id()));
                    if (active == null) continue;
                    Scope winner = null;
                    for (var scope : active)
                        if (scope.rule.includesTime(note.start()) && (winner == null || scope.index > winner.index))
                            winner = scope;
                    if (winner != null) {
                        roles.put(note.id(), Role.harmony);
                        reasons.put(note.id(), winner.reason());
                    }
                }
            }
            candidates.forEach((scope, notes) -> traceLine(notes, roles, programs, scope.rule.role(),
                    scope.rule.line() == ReconstructionRolePlan.Voice.bottom));
        }
        return reasons;
    }

    static ReconstructionMelody.Selection melody(ReconstructionStructure.Layout layout, List<Note> tones,
                                                  Map<Integer, Integer> programs) {
        var melody = ReconstructionMelody.extract(layout, tones, programs);
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

    private static double transition(Note prior, Note current, double gap) {
        int leap = Math.abs(prior.pitch() - current.pitch());
        double continuity = Math.max(0, 1 - Math.max(0, gap) / .40);
        return continuity * (.011 * leap + .021 * Math.max(0, leap - 5)
                + (prior.part() == current.part() ? 0 : .105)) + Math.min(.18, Math.abs(gap) * .20);
    }

    private static List<Decision> thin(List<Note> notes, Map<String, Role> roles, double hz,
                                       Map<String, String> reasons, Set<String> octaveDrops) {
        var decisions = new LinkedHashMap<String, Decision>();
        var latest = new HashMap<PartPitch, Note>();
        var chain = new HashMap<String, String>();
        var sounding = new ArrayList<Note>();
        int future = 0;
        for (var note : notes) {
            Role role = roles.get(note.id());
            decisions.put(note.id(), new Decision(note, role, reason(reasons, note.id(),
                    octaveDrops.contains(note.id()) ? "isolated-harmony-octave-double" : null), null));
            if (role == Role.drums || role == Role.drop || hz == 0) continue;
            while (future < notes.size() && notes.get(future).start() <= note.start() + .020) {
                Note incoming = notes.get(future++);
                if (roles.get(incoming.id()) != Role.drums && roles.get(incoming.id()) != Role.drop) sounding.add(incoming);
            }
            sounding.removeIf(n -> n.end() < note.start() - .020);
            var key = new PartPitch(note.part(), note.pitch());
            var previous = latest.put(key, note);
            if (role != Role.harmony || previous == null || roles.get(previous.id()) != Role.harmony) continue;
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
