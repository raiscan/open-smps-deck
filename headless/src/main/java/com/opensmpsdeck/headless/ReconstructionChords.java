package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionEdits.Kind;
import static com.opensmpsdeck.headless.ReconstructionEdits.Status;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static com.opensmpsdeck.headless.ReconstructionStages.Stage;

/** Selects co-attacks before lane allocation and encodes their one-channel playback. */
final class ReconstructionChords {
    enum Order { up, down, upDown }

    record Member(String id, int pitch, int endTick) {}
    record Group(String editId, int rate, @Nullable Order order, @Nullable Tuning tuning, List<Member> members) {
        boolean arpeggio() { return tuning == null; }
        int endTick() { return members.stream().mapToInt(Member::endTick).max().orElseThrow(); }
        boolean fmOnly() { return !arpeggio() || members.getFirst().pitch() < 45; }
        String reason() { return arpeggio() ? "absorbed-into-arpeggio" : "absorbed-into-chord-instrument"; }
        String label() {
            return arpeggio() ? "arpeggio" : "chord instrument (" + tuning.quality().name() + ")";
        }
    }

    record Step(Member member, int start, int end) {}
    record Tuning(Quality quality, int rootPitchClass, List<Integer> multiples, int note, int detune,
                  double maxErrorCents) {}
    private record Attack(int part, int tick) {}

    // Ratios relative to the identified root, in pitch-class order.
    enum Quality {
        major(new int[]{0, 4, 7}, new int[]{4, 5, 6}),
        minor(new int[]{0, 3, 7}, new int[]{10, 12, 15}),
        sus2(new int[]{0, 2, 7}, new int[]{8, 9, 12}),
        sus4(new int[]{0, 5, 7}, new int[]{6, 8, 9}),
        power(new int[]{0, 7}, new int[]{2, 3}),
        octaves(new int[]{0}, new int[]{1});

        final int[] intervals;
        final int[] ratios;
        Quality(int[] intervals, int[] ratios) {
            this.intervals = intervals;
            this.ratios = ratios;
        }
    }

    // Logical operators are written in S3K stored order 1, 3, 2, 4.
    private static final int[][] CARRIERS = {{3}, {3}, {3}, {3}, {1, 3}, {1, 2, 3}, {1, 2, 3}, {0, 1, 2, 3}};
    private static final double PHASE_HZ = 7670453.0 / (144 * (1 << 20));
    private static final double MAX_ERROR_CENTS = 18;

    private ReconstructionChords() {}

    static boolean isLabel(String value) {
        return value != null && (value.equals("arpeggio") || value.startsWith("chord instrument ("));
    }

    static boolean isAbsorbed(String reason) {
        return "absorbed-into-arpeggio".equals(reason) || "absorbed-into-chord-instrument".equals(reason);
    }

    static Map<String, Group> select(ReconstructionEdits edits, ReconstructionStages.Score score,
                                     ReconstructionRoles.Result roles, Map<String, ReconstructionLaneType> voiceTypes,
                                     List<ReconstructionEdits.Outcome> outcomes) {
        if (edits.intents().stream().noneMatch(edit -> edit.firstStage() == Stage.ALLOCATE)) return Map.of();
        var timeline = ReconstructionTimeMap.of(score);
        var decisions = new LinkedHashMap<String, ReconstructionRoles.Decision>();
        roles.notes().forEach(row -> decisions.put(row.note().id(), row));
        var bursts = new HashSet<String>();
        if (score.retriggers() != null) score.retriggers().forEach(burst -> bursts.add(burst.anchor()));
        var selected = new HashMap<String, Group>();
        for (var edit : edits.intents()) {
            if (edit.firstStage() != Stage.ALLOCATE) continue;
            var attacks = new LinkedHashMap<Attack, List<ReconstructionRoles.Decision>>();
            var targets = edit.span() == null ? edit.noteIds().stream().map(decisions::get)
                    .filter(java.util.Objects::nonNull).toList() : roles.notes().stream()
                    .filter(row -> edit.matchesSpan(row.note(), inBase(row.note().start(), roles, edit, timeline)))
                    .filter(row -> row.role() != Role.drop && row.role() != Role.drums).toList();
            for (var row : targets) {
                var note = row.note();
                attacks.computeIfAbsent(new Attack(note.part(), timeline.startTick(note)), key -> new ArrayList<>()).add(row);
            }
            var missing = edit.noteIds().stream().filter(id -> !decisions.containsKey(id)).toList();
            if (attacks.isEmpty() || !missing.isEmpty())
                outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), Status.orphaned,
                        List.of(), missing, "no matching allocated-stage note for selector", null));
            // For explicit IDs a missing tone could change the chord's identity.
            if (!missing.isEmpty()) continue;
            boolean foundChord = false;
            for (var rows : attacks.values()) {
                if (edit.span() != null && rows.size() < 2) continue;
                foundChord = true;
                var ids = rows.stream().map(row -> row.note().id()).toList();
                try {
                    if (ids.stream().anyMatch(selected::containsKey))
                        throw new IllegalArgumentException("a tone already belongs to an earlier chord edit");
                    for (var row : rows) {
                        if (row.role() == Role.drums || row.role() == Role.drop)
                            throw new IllegalArgumentException(row.note().id() + " has no playable tonal role");
                        if (row.note().run() != null || bursts.contains(row.note().id())
                                || row.note().bend() != null && row.note().bend().editReason() != null)
                            throw new IllegalArgumentException(row.note().id() + " has a figure, retrigger or authored bend");
                    }
                    var group = group(edit, rows, timeline);
                    String conflict = ids.stream().anyMatch(voiceTypes::containsKey)
                            ? "a chord member has a source voice instrument assignment"
                            : pinConflict(group, roles.lanePins());
                    if (conflict == null) {
                        ids.forEach(id -> selected.put(id, group));
                    } else {
                        outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), Status.conflict,
                                ids, null, conflict, null));
                    }
                } catch (IllegalArgumentException failure) {
                    outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), Status.invalid,
                            ids, null, failure.getMessage(), null));
                }
            }
            if (!attacks.isEmpty() && !foundChord)
                outcomes.add(new ReconstructionEdits.Outcome(edit.id(), edit.kind(), Status.invalid,
                        List.of(), null, "no co-attacking tones in this span", null));
        }
        return selected;
    }

    private static String pinConflict(Group group, Map<String, ReconstructionEdits.Pin> pins) {
        if (pins == null) return null;
        ReconstructionLaneType type = null;
        for (var member : group.members()) {
            var pin = pins.get(member.id());
            if (pin == null) continue;
            if (pin.laneType() == null) return member.id() + " has an exact lane pin to " + pin.lane();
            if (type != null && type != pin.laneType())
                return "chord members request different lane types (" + type + " and " + pin.laneType() + ")";
            type = pin.laneType();
        }
        if (type == null || type == ReconstructionLaneType.FM) return null;
        if (!group.arpeggio()) return "chord instrument requires FM, but a chord member requests " + type;
        if (type == ReconstructionLaneType.PSG && group.fmOnly()) return "PSG tone requires every chord pitch to be at least 45";
        if (type == ReconstructionLaneType.NOISE && group.members().stream().anyMatch(m -> !ReconstructionPitchedNoise.playable(m.pitch())))
            return "pitched noise cannot tune every chord member within 50 cents";
        return type == ReconstructionLaneType.DAC ? "DAC cannot voice a tonal arpeggio" : null;
    }

    private static double inBase(double time, ReconstructionRoles.Result roles, ReconstructionEdits.Intent edit,
                                 ReconstructionTimeMap timeline) {
        return edit.timeBase() == (roles.timeBase() == null ? timeline.timeBase() : roles.timeBase()) ? time
                : edit.timeBase() == ReconstructionTimeMap.TimeBase.source ? timeline.source(time) : timeline.project(time);
    }

    private static Group group(ReconstructionEdits.Intent edit, List<ReconstructionRoles.Decision> rows, ReconstructionTimeMap timeline) {
        var members = rows.stream().map(ReconstructionRoles.Decision::note)
                .sorted(Comparator.comparingInt(ReconstructionStages.Note::pitch))
                .map(note -> new Member(note.id(), note.pitch(), timeline.endTick(note))).toList();
        var pitches = members.stream().map(Member::pitch).toList();
        if (pitches.stream().distinct().count() < 2)
            throw new IllegalArgumentException("needs at least two distinct pitches in one part at the same driver tick");
        if (pitches.stream().distinct().count() != pitches.size())
            throw new IllegalArgumentException("duplicate unison tones in the selected chord");
        if (edit.kind() == Kind.chordInstrument)
            return new Group(edit.id(), 0, null, tune(pitches), members);
        var json = edit.json();
        int rate = json.has("rate") ? json.get("rate").getAsInt() : 2;
        var order = json.has("order") ? switch (json.get("order").getAsString()) {
            case "down" -> Order.down;
            case "up-down" -> Order.upDown;
            default -> Order.up;
        } : Order.up;
        var group = new Group(edit.id(), rate, order, null, members);
        int start = timeline.startTick(rows.getFirst().note());
        if (steps(group, start, Math.min(group.endTick(), start + rate * (2 * members.size() - 2))).stream().map(Step::member).distinct().count() < members.size())
            throw new IllegalArgumentException("gate is too short to sound every tone at " + rate + " ticks per arpeggio step");
        return group;
    }

    static Set<String> validate(Map<String, Group> groups, Map<String, ReconstructionAllocation.Entry> entries,
                                ReconstructionTimeMap timeline) {
        var absorbed = new HashSet<String>();
        var seen = new HashSet<String>();
        for (var row : groups.entrySet()) {
            String id = row.getKey();
            var group = row.getValue();
            String at = "allocation.json.chordVoicings." + id;
            var anchor = entries.get(id);
            ReconstructionArtifacts.require(anchor != null && anchor.lane() != null && anchor.startTick() != null
                            && anchor.endTick() != null && anchor.role() != Role.drums && anchor.role() != Role.drop
                            && group.members().size() >= 2 && group.members().stream().anyMatch(m -> m.id().equals(id))
                            && anchor.startTick() == timeline.startTick(anchor.note()) && anchor.endTick() <= group.endTick()
                            && (!group.fmOnly() || anchor.lane().startsWith("FM")
                                || group.arpeggio() && anchor.laneType() == ReconstructionLaneType.NOISE)
                            && (anchor.laneType() != ReconstructionLaneType.NOISE || group.members().stream()
                                .allMatch(member -> ReconstructionPitchedNoise.playable(member.pitch()))),
                    at, "expected an allocated anchor and compatible lane");
            int pitch = -1;
            for (var member : group.members()) {
                var entry = entries.get(member.id());
                ReconstructionArtifacts.require(entry != null && member.pitch() > pitch
                                && member.pitch() == entry.note().pitch() && member.endTick() == timeline.endTick(entry.note())
                                && entry.note().part() == anchor.note().part() && entry.role() != Role.drums
                                && entry.role() != Role.drop && timeline.startTick(entry.note()) == anchor.startTick()
                                && seen.add(member.id()), at, "invalid or overlapping chord member " + member.id());
                pitch = member.pitch();
                if (member.id().equals(id)) continue;
                ReconstructionArtifacts.require(id.equals(entry.mergedInto()) && group.reason().equals(entry.reason())
                                && entry.outcome() == ReconstructionAllocation.Outcome.dropped,
                        at, "expected absorbed member " + member.id());
                absorbed.add(member.id());
            }
            if (group.arpeggio()) {
                ReconstructionArtifacts.require(group.rate() >= 1 && group.rate() <= 127 && group.order() != null,
                        at, "invalid arpeggio rate or order");
            } else {
                ReconstructionArtifacts.require(group.rate() == 0 && group.order() == null
                                && group.tuning().equals(tune(group.members().stream().map(Member::pitch).toList())),
                        at, "invalid chord tuning");
            }
        }
        return absorbed;
    }

    static List<Step> steps(Group group, int start, int end) {
        var cycle = new ArrayList<>(group.order() == Order.down ? group.members().reversed() : group.members());
        if (group.order() == Order.upDown) cycle.addAll(group.members().reversed().subList(1, cycle.size() - 1));
        var steps = new ArrayList<Step>();
        int next = 0;
        for (int at = start; at < end;) {
            // Once a tone reaches its written end, advance through the remaining pitches.
            int tick = at;
            var active = group.members().stream().filter(member -> member.endTick() > tick).toList();
            if (active.isEmpty()) break;
            if (active.size() == 1) {
                steps.add(new Step(active.getFirst(), at, Math.min(end, active.getFirst().endTick())));
                break;
            }
            while (cycle.get(next).endTick() <= at) next = (next + 1) % cycle.size();
            var member = cycle.get(next);
            int to = Math.min(Math.min(end, member.endTick()), at + group.rate());
            steps.add(new Step(member, at, to));
            at = to;
            next = (next + 1) % cycle.size();
        }
        return steps;
    }

    static Tuning tune(List<Integer> pitches) {
        if (pitches.size() > 4) throw new IllegalArgumentException("FM has at most four carriers; selected " + pitches.size() + " tones");
        var pitchClasses = new HashSet<Integer>();
        pitches.forEach(pitch -> pitchClasses.add(pitch % 12));
        boolean recognized = false, fitsMultiples = false;
        // With symmetric suspended chords, prefer a root in the bass when possible.
        for (int root : pitches) {
            var intervals = new HashSet<Integer>();
            pitchClasses.forEach(pc -> intervals.add(Math.floorMod(pc - root, 12)));
            for (var quality : Quality.values()) {
                if (quality.intervals.length != intervals.size()
                        || !Arrays.stream(quality.intervals).allMatch(intervals::contains)) continue;
                recognized = true;
                var multiples = multiples(pitches, root, quality);
                if (multiples.stream().anyMatch(multiple -> multiple > 15)) continue;
                fitsMultiples = true;
                var tuning = fit(pitches, root % 12, quality, multiples);
                if (tuning != null) return tuning;
            }
        }
        if (!recognized)
            throw new IllegalArgumentException("unsupported chord quality (use major, minor, sus2, sus4, power or octaves)");
        if (!fitsMultiples)
            throw new IllegalArgumentException("voicing spread exceeds YM2612 carrier multiples 1–15");
        throw new IllegalArgumentException("base pitch is outside the S3K F-number/block and signed detune range at ±18 cents");
    }

    private static List<Integer> multiples(List<Integer> pitches, int root, Quality quality) {
        int lowestOctave = Math.floorDiv(pitches.getFirst() - root, 12);
        var ratios = pitches.stream().map(pitch -> {
            int interval = pitch - root;
            int ratio = quality.ratios[Arrays.binarySearch(quality.intervals, Math.floorMod(interval, 12))];
            return ratio << (Math.floorDiv(interval, 12) - lowestOctave);
        }).toList();
        int gcd = ratios.stream().reduce(ReconstructionChords::gcd).orElseThrow();
        return ratios.stream().map(ratio -> ratio / gcd).toList();
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private static Tuning fit(List<Integer> pitches, int root, Quality quality, List<Integer> multiples) {
        // Minimize the largest carrier error. The geometric center equalizes the
        // flat and sharp extremes in cents. Driver tuning uses a signed E1 byte.
        double low = Double.POSITIVE_INFINITY, high = 0;
        for (int i = 0; i < pitches.size(); i++) {
            double hz = 440 * Math.pow(2, (pitches.get(i) - 69) / 12.0) / multiples.get(i);
            low = Math.min(low, hz);
            high = Math.max(high, hz);
        }
        Tuning best = null;
        double center = Math.sqrt(low * high);
        for (int midi = 12; midi <= 106; midi++) {
            int block = (midi - 12) / 12;
            int nativeFnum = LeadEffectMatcher.fmFnum(midi);
            int ideal = (int) Math.round(center / (PHASE_HZ * Math.scalb(1, block - 1)));
            int offset = Math.clamp(ideal - nativeFnum, -128, 127);
            for (int detune = Math.max(-128, offset - 1); detune <= Math.min(127, offset + 1); detune++) {
                int fnum = nativeFnum + detune;
                if (fnum < 1 || fnum > 2047) continue;
                double actual = ((fnum << block) >> 1) * PHASE_HZ;
                double error = 1200 * Math.max(Math.abs(Math.log(actual / low)), Math.abs(Math.log(actual / high))) / Math.log(2);
                if (error <= MAX_ERROR_CENTS && (best == null || error < best.maxErrorCents()
                        || error == best.maxErrorCents() && Math.abs(detune) < Math.abs(best.detune())))
                    best = new Tuning(quality, root, multiples, midi, detune, error);
            }
        }
        return best;
    }

    static Voice patch(String name, byte[] source, Tuning tuning, Set<Integer> active) {
        int oldAlgorithm = source[0] & 7;
        int size = tuning.multiples().size();
        int algorithm = switch (size) {
            case 2 -> 4; // Two independent modulator → carrier pairs.
            case 3 -> 5; // One modulator feeds three carriers.
            case 4 -> 7; // Four independent carriers.
            default -> throw new IllegalArgumentException("FM chord requires two to four carriers");
        };
        var carrierSlots = CARRIERS[algorithm];
        int model = Arrays.stream(CARRIERS[oldAlgorithm]).boxed()
                .min(Comparator.comparingInt(slot -> source[21 + slot] & 127)).orElseThrow();
        var patch = new byte[25];
        patch[0] = (byte) algorithm;
        for (int slot = 0; slot < 4; slot++) patch[21 + slot] = 127;
        // Preserve the catalogue carrier's envelope, scaling and level. Use a restrained
        // catalogue modulator where topology leaves room, with no operator detune.
        if (algorithm != 7) {
            copyOperator(source, 0, patch, 0);
            patch[1] = tuning.multiples().getFirst().byteValue();
            patch[21] = (byte) Math.max(56, source[21] & 127);
        }
        if (algorithm == 4) {
            copyOperator(patch, 0, patch, 2);
            patch[3] = tuning.multiples().getLast().byteValue();
        }
        int headroom = (int) Math.ceil(20 * Math.log10(size) / .75);
        for (int i = 0; i < carrierSlots.length; i++) {
            if (!active.contains(i)) continue;
            int slot = carrierSlots[i];
            copyOperator(source, model, patch, slot);
            patch[1 + slot] = tuning.multiples().get(i).byteValue();
            patch[21 + slot] = (byte) (128 | Math.min(126, (source[21 + model] & 127) + headroom));
        }
        var bytes = new ArrayList<Integer>(patch.length);
        for (byte b : patch) bytes.add(Byte.toUnsignedInt(b));
        String id = "chord-" + java.util.HexFormat.of().formatHex(patch);
        return new Voice(id, "Chord instrument / " + tuning.quality() + " / " + name, List.copyOf(bytes));
    }

    private static void copyOperator(byte[] from, int source, byte[] to, int slot) {
        for (int i = 1; i < 25; i += 4) to[i + slot] = from[i + source];
    }
}
