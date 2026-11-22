package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

record ReconstructionRolePlan(int version, List<ReconstructionRolePlan.Rule> rules,
                              @Nullable ReconstructionTimeMap.TimeBase timeBase) {
    record ResolvedTime(int rule, double sourceStart, double sourceEnd, double projectStart,
                        double projectEnd, long startTick, long endTick) {}

    ReconstructionTimeMap.TimeBase timeBase(Rule rule, ReconstructionTimeMap timeline) {
        if (timeBase != null) return timeBase;
        return rule.sections == null && rule.bars == null
                ? ReconstructionTimeMap.TimeBase.source : timeline.timeBase();
    }

    ReconstructionRolePlan projectBounds(ReconstructionTimeMap timeline) {
        return new ReconstructionRolePlan(version, rules.stream().map(rule -> {
            if (timeBase(rule, timeline) != ReconstructionTimeMap.TimeBase.source || !timeline.warped()) return rule;
            return new Rule(rule.sections, rule.bars, rule.part, rule.role, rule.voice, rule.pitchMin, rule.pitchMax,
                    rule.startSeconds == null ? null : timeline.project(rule.startSeconds),
                    rule.endSeconds == null ? null : timeline.project(rule.endSeconds), rule.texture, rule.section);
        }).toList(), timeline.timeBase());
    }

    ReconstructionRolePlan resolveSections(ReconstructionSections.Result form, ReconstructionTimeMap timeline) {
        var project = projectBounds(timeline);
        return new ReconstructionRolePlan(version, project.rules.stream().map(rule -> {
            if (rule.section == null) return rule;
            var span = form.select(rule.section);
            ReconstructionArtifacts.require(span.level() != ReconstructionSections.Level.phrase, "roleplan.json",
                    "section must name a section or period: " + rule.section);
            double start = rule.startSeconds == null ? span.projectStart() : Math.max(span.projectStart(), rule.startSeconds);
            double end = rule.endSeconds == null ? span.projectEnd() : Math.min(span.projectEnd(), rule.endSeconds);
            ReconstructionArtifacts.require(start < end, "roleplan.json", "time bounds do not intersect " + rule.section);
            return new Rule(rule.sections, rule.bars, rule.part, rule.role, rule.voice, rule.pitchMin, rule.pitchMax,
                    start, end, rule.texture);
        }).toList(), project.timeBase);
    }

    List<ResolvedTime> resolvedTimes(ReconstructionTimeMap timeline) {
        var projected = projectBounds(timeline);
        var result = new ArrayList<ResolvedTime>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = projected.rules.get(i);
            if (rule.startSeconds == null && rule.endSeconds == null) continue;
            double start = rule.startSeconds == null ? 0 : rule.startSeconds;
            double end = rule.endSeconds == null ? timeline.projectDuration() : rule.endSeconds;
            // Rules test continuous attack time. The half-open bounds below identify the eligible tick interval.
            result.add(new ResolvedTime(i, timeline.source(start), timeline.source(end), start, end,
                    timeline.firstTickAtOrAfter(start), timeline.firstTickAtOrAfter(end)));
        }
        return result;
    }

    enum Voice { top, bottom, all }

    record Rule(@Nullable List<String> sections, @Nullable String bars, int part, Role role,
                @Nullable Voice voice, @Nullable Integer pitchMin, @Nullable Integer pitchMax,
                @Nullable Double startSeconds, @Nullable Double endSeconds, @Nullable String texture,
                @Nullable String section) {
        Rule(List<String> sections, String bars, int part, Role role, Voice voice, Integer pitchMin, Integer pitchMax,
             Double startSeconds, Double endSeconds, String texture) {
            this(sections, bars, part, role, voice, pitchMin, pitchMax, startSeconds, endSeconds, texture, null);
        }

        Voice line() {
            return voice == null ? defaultVoice(role) : voice;
        }

        boolean includesTime(double start) {
            return (startSeconds == null || start >= startSeconds) && (endSeconds == null || start < endSeconds);
        }

        boolean filtersTexture() {
            return texture != null && !texture.equals("any");
        }

        boolean includes(Note note, boolean chord) {
            return note.part() == part && includesTime(note.start()) && (pitchMin == null || pitchMin <= note.pitch())
                    && (pitchMax == null || pitchMax >= note.pitch())
                    && (!filtersTexture() || texture.equals(chord ? "chord" : "single"));
        }
    }

    record BarRange(int first, int last) {
        static BarRange parse(String value, int count, String at) {
            ReconstructionArtifacts.require(value.matches("[1-9][0-9]*-[1-9][0-9]*"), at,
                    "expected an inclusive one-based bar range, e.g. 17-24");
            try {
                String[] ends = value.split("-");
                int first = Integer.parseInt(ends[0]);
                int last = Integer.parseInt(ends[1]);
                ReconstructionArtifacts.require(first <= last && last <= count, at, "bars must be within 1.." + count);
                return new BarRange(first, last);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(at + ": bar numbers out of range", invalid);
            }
        }
    }

    static boolean traced(Role role) {
        return role == Role.melody || role == Role.bass || role == Role.counter;
    }

    private static Voice defaultVoice(Role role) {
        return switch (role) {
            case melody, counter -> Voice.top;
            case bass -> Voice.bottom;
            default -> Voice.all;
        };
    }

    static ReconstructionRolePlan template(ReconstructionStructure.Result structure,
                                           ReconstructionRoles.Result heuristic) {
        var notes = ReconstructionRoles.byBar(structure.bars(), heuristic.notes().stream()
                .map(ReconstructionRoles.Decision::note).toList());
        var roles = new HashMap<String, Role>();
        for (var decision : heuristic.notes()) roles.put(decision.note().id(), decision.role());
        var rules = new ArrayList<Rule>();
        for (var section : structure.sections()) {
            var counts = new TreeMap<Integer, Map<Role, Integer>>();
            for (String bar : section.bars()) for (var note : notes.get(bar))
                counts.computeIfAbsent(note.part(), ignored -> new java.util.EnumMap<>(Role.class))
                        .merge(roles.get(note.id()), 1, Integer::sum);
            counts.forEach((part, tally) -> {
                Role role = Role.harmony;
                int most = 0;
                // Enum order breaks ties in favor of audible lines.
                for (Role candidate : Role.values()) {
                    int count = tally.getOrDefault(candidate, 0);
                    if (count > most) {
                        most = count;
                        role = candidate;
                    }
                }
                rules.add(new Rule(List.of(section.id()), null, part, role, defaultVoice(role), null, null, null, null, null));
            });
        }
        return new ReconstructionRolePlan(ReconstructionStages.VERSION, rules, ReconstructionTimeMap.TimeBase.source);
    }
}
