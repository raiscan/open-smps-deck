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
    enum Voice { top, bottom, all }

    record Rule(@Nullable List<String> sections, @Nullable String bars, int part, Role role,
                @Nullable Voice voice, @Nullable Integer pitchMin, @Nullable Integer pitchMax,
                @Nullable Double startSeconds, @Nullable Double endSeconds) {
        Voice line() {
            return voice == null ? defaultVoice(role) : voice;
        }

        boolean includesTime(double start) {
            return (startSeconds == null || start >= startSeconds) && (endSeconds == null || start < endSeconds);
        }

        boolean includes(Note note) {
            return note.part() == part && includesTime(note.start()) && (pitchMin == null || pitchMin <= note.pitch())
                    && (pitchMax == null || pitchMax >= note.pitch());
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
                rules.add(new Rule(List.of(section.id()), null, part, role, defaultVoice(role), null, null, null, null));
            });
        }
        var timeBase = structure.timeBase() == null ? ReconstructionTimeMap.TimeBase.source : structure.timeBase();
        return new ReconstructionRolePlan(ReconstructionStages.VERSION, rules, timeBase);
    }
}
