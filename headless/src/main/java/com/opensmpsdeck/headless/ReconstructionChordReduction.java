package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

final class ReconstructionChordReduction {
    enum Tone {
        doubling(0), rootInBass(0), fifth(1), extension(2), third(3), seventh(3), topVoice(4), root(4);

        final int priority;

        Tone(int priority) { this.priority = priority; }
    }

    record Voice(Note note, Role role) {}
    record Loss(String noteId, int part, int pitch, double sourceStart, Tone tone, String reason,
                Role role, List<String> competingNoteIds) {}
    record SectionCounts(String label, double sourceStart, double sourceEnd, Map<Tone, Integer> counts) {}
    record Audit(Map<Tone, Integer> song, Map<String, SectionCounts> sections, List<Loss> losses) {}

    private ReconstructionChordReduction() {}

    static Map<String, Tone> classify(List<Voice> notes) {
        return classify(notes, note -> false);
    }

    static Map<String, Tone> classify(List<Voice> notes, java.util.function.Predicate<Note> competingWithLead) {
        var ordered = notes.stream().sorted(Comparator.comparing(Voice::note, ReconstructionStages.NOTE_ORDER)).toList();
        var result = new HashMap<String, Tone>();
        var tops = new HashSet<String>();
        var accompaniment = ordered.stream().filter(v -> v.role != Role.bass && v.role != Role.melody).toList();
        for (var cluster : ReconstructionOnsetClusters.group(accompaniment, v -> v.note.part(), v -> v.note.start())) {
            int high = cluster.notes().stream().mapToInt(v -> v.note.pitch()).max().orElseThrow();
            if (cluster.notes().stream().anyMatch(v -> v.note.pitch() < high)) {
                for (var voice : cluster.notes()) if (voice.note.pitch() == high) tops.add(voice.note.id());
            }
        }
        var active = new ArrayList<Voice>();
        int next = 0;
        for (var voice : ordered) {
            double onset = voice.note.start();
            while (next < ordered.size() && ordered.get(next).note.start() <= onset + .020)
                active.add(ordered.get(next++));
            active.removeIf(other -> other.note.end() <= onset);
            result.put(voice.note.id(), classify(voice, active, tops.contains(voice.note.id()), competingWithLead.test(voice.note)));
        }
        return result;
    }

    private static Tone classify(Voice voice, List<Voice> active, boolean top, boolean upperLine) {
        int pitch = voice.note.pitch();
        int pitchClass = pitch % 12;
        int bass = -1, lowest = 128;
        int classes = 0;
        boolean bassOctave = false;
        for (var other : active) {
            int sounding = other.note.pitch();
            lowest = Math.min(lowest, sounding);
            classes |= 1 << (sounding % 12);
            if (other.note.id().equals(voice.note.id())) continue;
            if (upperLine && sounding < pitch && sounding % 12 == pitchClass && other.note.part() == voice.note.part()
                    && Math.abs(other.note.start() - voice.note.start()) <= .020) return Tone.doubling;
            if (other.role == Role.bass && (bass < 0 || sounding < bass)) bass = sounding;
            if ((other.role == Role.bass || other.role == Role.melody)
                    && sounding % 12 == pitchClass && Math.abs(sounding - pitch) <= 12) {
                if (other.role == Role.melody || sounding == pitch) return Tone.doubling;
                bassOctave = true;
            }
        }
        int root = root(classes, bass >= 0 ? bass % 12 : lowest % 12);
        if (bass >= 0 && bass % 12 == pitchClass && root == pitchClass) return Tone.rootInBass;
        if (bassOctave) return Tone.doubling;
        if (top) return Tone.topVoice;
        if (upperLine && root == pitchClass) return Tone.root;
        return switch (Math.floorMod(pitchClass - root, 12)) {
            case 3, 4 -> Tone.third;
            case 7 -> Tone.fifth;
            case 10, 11 -> Tone.seventh;
            default -> Tone.extension;
        };
    }

    private static int root(int classes, int preferred) {
        int best = preferred, bestScore = Integer.MIN_VALUE;
        for (int root = 0; root < 12; root++) {
            if (!contains(classes, root, 0)) continue;
            int score = root == preferred ? 3 : 0;
            if (contains(classes, root, 3) || contains(classes, root, 4)) score += 4;
            if (contains(classes, root, 7)) score += 3;
            if (contains(classes, root, 10) || contains(classes, root, 11)) score++;
            if (score > bestScore) {
                best = root;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean contains(int classes, int root, int interval) {
        return (classes & (1 << ((root + interval) % 12))) != 0;
    }

    static Audit audit(ReconstructionStages.Score score, ReconstructionAllocation.Result allocation,
                       ReconstructionSections.Result sections, List<S3kReconstruction.Reduction> assemblyLosses) {
        var drums = new HashSet<Integer>();
        for (var part : score.source().parts()) if (part.drums()) drums.add(part.id());
        var timeline = ReconstructionTimeMap.of(score);
        var voices = new ArrayList<Voice>();
        var lost = new ArrayList<Voice>();
        var reasons = new HashMap<String, String>();
        var competitors = new HashMap<String, List<String>>();
        boolean audioOnly = score.inputs() != null && score.inputs().audioOnly() != null;
        var unmatched = new HashSet<String>();
        for (var reduction : assemblyLosses)
            if (reduction.change() == S3kReconstruction.Change.NO_MATCH) unmatched.add(reduction.id());
        for (var entry : allocation.notes()) {
            if (drums.contains(entry.note().part()) || entry.role() == Role.drums) continue;
            var voice = new Voice(entry.note(), entry.role());
            voices.add(voice);
            if (entry.lane() == null || unmatched.contains(entry.note().id())) {
                lost.add(voice);
                reasons.put(entry.note().id(), entry.lane() == null ? entry.reason() : "no-playable-catalogue-match");
                if (audioOnly && entry.blockingNoteIds() != null)
                    competitors.put(entry.note().id(), entry.blockingNoteIds());
                else if (audioOnly && entry.competitorNoteId() != null)
                    competitors.put(entry.note().id(), List.of(entry.competitorNoteId()));
            }
        }
        int index = 0;
        for (var reduction : score.reductions()) {
            var midi = reduction.note();
            if (drums.contains(midi.partId()) || reduction.change() == S3kReconstruction.Change.CUT_AT_END) continue;
            String id = reduction.id() == null ? "score-reduction-" + index++ : reduction.id();
            var note = new Note(id, midi.partId(), midi.startUs() / 1e6,
                    midi.endUs() / 1e6, midi.pitch(), midi.velocity(), "midi", 1);
            var voice = new Voice(timeline.projectNote(note), Role.drop);
            lost.add(voice);
            voices.add(voice);
            reasons.put(note.id(), reduction.reason() == null ? reduction.change().name() : reduction.reason());
        }
        if (score.scoreDecisions() != null) for (var decision : score.scoreDecisions()) {
            if (reasons.containsKey(decision.note().id()) || drums.contains(decision.note().part())) continue;
            var voice = new Voice(timeline.projectNote(decision.note()), Role.drop);
            lost.add(voice);
            reasons.put(voice.note.id(), decision.reason());
            voices.add(voice);
        }

        var tones = classify(voices);
        var counts = counts();
        var bySection = new LinkedHashMap<String, SectionCounts>();
        for (var section : sections.sections()) if (section.level() == ReconstructionSections.Level.section)
            bySection.put(section.id(), new SectionCounts(section.label(), section.sourceStart(), section.sourceEnd(), counts()));
        var losses = new ArrayList<Loss>();
        for (var voice : lost.stream().sorted(Comparator.comparing(Voice::note, ReconstructionStages.NOTE_ORDER)).toList()) {
            var note = voice.note;
            double start = timeline.source(note.start());
            var tone = tones.get(note.id());
            if (List.of("OCTAVE_DOUBLE", "stem-midi-doubling").contains(reasons.get(note.id()))) tone = Tone.doubling;
            counts.merge(tone, 1, Integer::sum);
            for (var section : bySection.values()) {
                if (start >= section.sourceStart && start < section.sourceEnd) section.counts.merge(tone, 1, Integer::sum);
            }
            losses.add(new Loss(note.id(), note.part(), note.pitch(), start, tone, reasons.get(note.id()),
                    audioOnly ? voice.role() : null, competitors.get(note.id())));
        }
        return new Audit(counts, bySection, List.copyOf(losses));
    }

    private static Map<Tone, Integer> counts() {
        var result = new EnumMap<Tone, Integer>(Tone.class);
        for (var tone : Tone.values()) if (tone != Tone.root) result.put(tone, 0);
        return result;
    }
}
