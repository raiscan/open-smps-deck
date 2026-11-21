package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Musical evidence for separating source instruments within a MIDI part. */
final class ReconstructionVoiceProposal {
    private static final int MIN_VOICE_NOTES = 8;
    private static final int MIN_LINE_ATTACKS = 3;
    private static final int MIN_CHORD_ATTACKS = 4;
    private static final double PHRASE_GAP = .75;

    record Attack(Note note, Role role, double onset, double end, String section) {
        int part() { return note.part(); }
    }

    record Group(String suffix, String label, String register, String basis, double confidence, List<Attack> attacks,
                 String attributedStem) {
        Group(String suffix, String label, String register, String basis, double confidence, List<Attack> attacks) {
            this(suffix, label, register, basis, confidence, attacks, null);
        }
    }

    private ReconstructionVoiceProposal() {}

    static List<Group> identify(List<Attack> attacks, String stem, ReconstructionVoiceStems audio) {
        var audible = attacks.stream().filter(attack -> attack.role != Role.drop).toList();
        var clusters = clusters(audible);
        int boundary = registerBoundary(audible, clusters);
        if (boundary >= 0) {
            var low = attacks.stream().filter(attack -> attack.note.pitch() < boundary).toList();
            var high = attacks.stream().filter(attack -> attack.note.pitch() >= boundary).toList();
            String bass = mostly(low, Role.bass) ? "bass" : "lower";
            String treble = mostly(high, Role.harmony) ? "comp" : "upper";
            String basis = "octave-separated pitches with recurring simultaneous attacks";
            return List.of(new Group("lower", bass, "lower", basis, .78, low),
                    new Group("upper", treble, "upper", basis, .78, high));
        }

        // Only one line attack per cluster. An inner note labelled counter
        // may be a chord tone when the melody attacks over it.
        var line = new HashSet<String>();
        var passages = new TreeMap<String, Passage>();
        var melodicRole = audible.stream().filter(attack -> attack.role == Role.melody).count() >= MIN_VOICE_NOTES
                ? Role.melody : Role.counter;
        for (var cluster : clusters) {
            var melody = cluster.notes().stream().filter(attack -> attack.role == melodicRole)
                    .max(Comparator.comparingInt(attack -> attack.note.pitch())).orElse(null);
            var passage = passages.computeIfAbsent(cluster.notes().getFirst().section, ignored -> new Passage());
            if (melody != null) {
                line.add(melody.note.id());
                if (cluster.start() - passage.previousLine <= PHRASE_GAP) passage.connected++;
                passage.previousLine = cluster.start();
                if (cluster.notes().size() == 1) passage.solo++;
            }
            boolean accompaniment = cluster.notes().stream().anyMatch(attack -> attack != melody
                    && (attack.role == Role.harmony || attack.role == Role.counter));
            if (cluster.notes().size() >= 2 && accompaniment) passage.stacks++;
        }

        int solo = passages.values().stream().mapToInt(passage -> passage.solo).max().orElse(0);
        int stacks = passages.values().stream().mapToInt(passage -> passage.stacks).max().orElse(0);
        int connected = passages.values().stream().mapToInt(passage -> passage.connected).max().orElse(0);
        if (line.size() >= MIN_VOICE_NOTES && audible.size() - line.size() >= MIN_VOICE_NOTES
                && solo >= MIN_LINE_ATTACKS && stacks >= MIN_CHORD_ATTACKS && connected >= MIN_LINE_ATTACKS) {
            var melody = attacks.stream().filter(attack -> line.contains(attack.note.id())).toList();
            var backing = attacks.stream().filter(attack -> !line.contains(attack.note.id())).toList();
            double confidence = solo >= 8 && stacks >= 8 ? .89 : .78;
            String lineId = melodicRole == Role.melody ? "line" : "counter-line";
            return List.of(new Group(lineId, lineId.replace('-', ' '), null,
                            "recurring " + melodicRole + " attacks with an independent line in a section", confidence, melody),
                    new Group("chords", "chords", null, "recurring stacked accompaniment in a section", confidence, backing));
        }

        var acoustic = audio.split(attacks);
        if (!acoustic.isEmpty()) return acoustic;
        return List.of(new Group("", null, null, "MIDI part", stem == null ? .75 : .9, attacks));
    }

    static List<ReconstructionOnsetClusters.Cluster<Attack>> clusters(List<Attack> attacks) {
        return ReconstructionOnsetClusters.group(attacks, Attack::part, Attack::onset);
    }

    static Map<String, Integer> counts(List<Attack> attacks, Function<Attack, String> classifier) {
        return attacks.stream().collect(Collectors.toMap(classifier, ignored -> 1, Integer::sum, TreeMap::new));
    }

    private static boolean mostly(List<Attack> attacks, Role role) {
        return attacks.stream().filter(attack -> attack.role == role).count() * 2 > attacks.size();
    }

    // An octave jump can belong to one instrument. Require the bands to
    // coexist repeatedly in the same musical section.
    private static int registerBoundary(List<Attack> attacks, List<ReconstructionOnsetClusters.Cluster<Attack>> clusters) {
        var pitches = attacks.stream().mapToInt(attack -> attack.note.pitch()).sorted().toArray();
        int gap = 11, boundary = -1;
        for (int i = MIN_VOICE_NOTES; i <= pitches.length - MIN_VOICE_NOTES; i++) {
            int distance = pitches[i] - pitches[i - 1];
            if (distance <= gap) continue;
            int pitch = pitches[i];
            var coattacks = new TreeMap<String, Integer>();
            for (var cluster : clusters) {
                if (cluster.notes().stream().anyMatch(attack -> attack.note.pitch() < pitch)
                        && cluster.notes().stream().anyMatch(attack -> attack.note.pitch() >= pitch))
                    coattacks.merge(cluster.notes().getFirst().section, 1, Integer::sum);
            }
            if (coattacks.values().stream().anyMatch(count -> count >= 3)) {
                boundary = pitch;
                gap = distance;
            }
        }
        return boundary;
    }

    private static final class Passage {
        int solo;
        int stacks;
        int connected;
        double previousLine = Double.NEGATIVE_INFINITY;
    }
}
