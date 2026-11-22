package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;

final class ReconstructionStemParts {
    private static final int MAX_MISSING_FRAMES = 3;
    private static final int MIN_VOICING_FRAMES = 15;
    static final String HARMONIC_GHOST = "stem-upper-harmonic";
    private static final int[] HARMONIC_INTERVALS = {12, 19, 24};

    private ReconstructionStemParts() {}

    static Set<Integer> addedParts(ReconstructionStages.Inputs inputs) {
        if (inputs == null || inputs.externalNotes() == null) return Set.of();
        return inputs.externalNotes().entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue().provenance().addedStem()))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static boolean startsAudibly(byte[] envelope) {
        // A backing patch should start with its note and cannot reset through
        // silence while its gate is still open. A natural one-shot decay is fine.
        if (envelope.length == 0 || Byte.toUnsignedInt(envelope[0]) >= 15) return false;
        boolean muted = false;
        for (byte raw : envelope) {
            int value = Byte.toUnsignedInt(raw);
            if (value == 0x81 || value == 0x83) return true;
            if (value == 0x80) return !muted;
            if (value >= 15) muted = true;
            else if (muted) return false;
        }
        return true;
    }

    static List<Note> joinHolds(List<Note> candidates, Spectrum spectrum, List<S3kReconstruction.Reduction> reductions) {
        var joined = new ArrayList<Note>();
        var ordered = candidates.stream().sorted(Comparator.comparingInt(Note::pitch)
                .thenComparing(ReconstructionStages.NOTE_ORDER)).toList();
        for (var next : ordered) {
            var prior = joined.isEmpty() ? null : joined.getLast();
            if (prior == null || prior.pitch() != next.pitch() || prior.part() != next.part()
                    || next.start() - prior.end() > .050 || !spectrum.continuous(prior, next)) {
                joined.add(next);
                continue;
            }
            joined.set(joined.size() - 1, new Note(prior.id(), prior.part(), prior.start(),
                    Math.max(prior.end(), next.end()), prior.pitch(), prior.velocity(), prior.source(), prior.confidence()));
            reductions.add(new S3kReconstruction.Reduction(next.midi(),
                    S3kReconstruction.Change.STEM_FRAGMENT, next.id(), "stem-hold-fragment"));
        }
        return joined;
    }

    static boolean tonalFamily(String family) {
        return family != null && switch (family) {
            case "keyboard", "synth", "bass", "strings", "brass", "guitar", "woodwinds" -> true;
            default -> false;
        };
    }

    static MidiScore.Part part(int id, String role) {
        int program = switch (role.toLowerCase(Locale.ROOT)) {
            case "bass" -> 33;
            case "guitar" -> 27;
            case "strings" -> 48;
            case "brass" -> 61;
            case "woodwinds" -> 73;
            case "synth" -> 89;
            default -> 4;
        };
        return new MidiScore.Part(id, role, program, false);
    }

    static Map<String, Double> salience(ReconstructionStages.Score score, ReconstructionRoles.Result roles)
            throws IOException {
        var levels = new HashMap<String, Double>();
        var timeline = ReconstructionTimeMap.of(score);
        for (var stem : score.inputs().stems().entrySet()) {
            var notes = roles.notes().stream().filter(d -> d.note().part() == stem.getKey()
                    && (d.role() == ReconstructionRoles.Role.harmony || d.role() == ReconstructionRoles.Role.counter))
                    .map(d -> timeline.sourceNote(d.note())).toList();
            if (notes.isEmpty()) continue;
            var spectrum = Spectrum.read(Path.of(stem.getValue()), score.durationUs() / 1e6);
            for (var note : notes) levels.put(note.id(), spectrum.level(note, note.pitch()));
        }
        return levels;
    }

    static ArrayList<Note> notes(int part, List<ExternalTranscribers.RawNote> raw, double duration, boolean added) {
        var notes = new ArrayList<Note>();
        for (int index = 0; index < raw.size(); index++) {
            var note = raw.get(index);
            if (note.start() < duration && note.pitch() >= 12 && note.pitch() <= 106)
                notes.add(new Note("transcriber-" + part + "-" + index, part, note.start(),
                        Math.min(duration, note.end()), note.pitch(), note.velocity(),
                        added ? "audio-transcribed" : "transcriber", 1));
        }
        return notes;
    }

    static List<Note> upperVoicing(int part, List<ExternalTranscribers.RawNote> raw, Spectrum spectrum,
                                   double duration, List<S3kReconstruction.Reduction> reductions) {
        if (raw.isEmpty()) return List.of();
        var track = new ReconstructionLeadAudio.Track(spectrum.audio, true);
        var ordered = raw.stream().sorted(Comparator.comparingDouble(ExternalTranscribers.RawNote::start)).toList();
        var byPitch = ordered.stream().collect(java.util.stream.Collectors.groupingBy(ExternalTranscribers.RawNote::pitch));
        var sounding = new ArrayList<ExternalTranscribers.RawNote>();
        var recovered = new ArrayList<Note>();
        var supported = new boolean[128];
        var decoded = new boolean[128];
        var since = new int[128];
        var latest = new int[128];
        Arrays.fill(since, -1);
        int cursor = 0;
        for (int frame = 0; frame <= track.frames(); frame++) {
            double time = frame * ReconstructionLeadAudio.HOP;
            while (cursor < ordered.size() && ordered.get(cursor).start() <= time)
                sounding.add(ordered.get(cursor++));
            sounding.removeIf(note -> note.end() <= time);
            Arrays.fill(decoded, false);
            int highest = 0;
            int lowest = 127;
            for (var note : sounding) {
                decoded[note.pitch()] = true;
                highest = Math.max(highest, note.pitch());
                lowest = Math.min(lowest, note.pitch());
            }
            boolean polyphonic = lowest < highest;
            if (frame < track.frames() && polyphonic) track.upperPitches(frame, highest, supported);
            else Arrays.fill(supported, false);
            for (int pitch = 0; pitch < supported.length; pitch++) {
                if (supported[pitch]) {
                    if (since[pitch] < 0) since[pitch] = frame;
                    latest[pitch] = frame;
                } else if (since[pitch] >= 0 && (frame - latest[pitch] > MAX_MISSING_FRAMES
                        || decoded[pitch] || frame == track.frames())) {
                    int onset = since[pitch], release = latest[pitch] + 1;
                    if (release - onset >= MIN_VOICING_FRAMES) {
                        var note = new Note("transcriber-" + part + "-upper-" + pitch + "-" + onset, part,
                                onset * ReconstructionLeadAudio.HOP, Math.min(duration, release * ReconstructionLeadAudio.HOP),
                                pitch, 80, "audio-transcribed", .8);
                        if (harmonicGhost(note, byPitch, track)) {
                            reductions.add(new S3kReconstruction.Reduction(note.midi(),
                                    S3kReconstruction.Change.STEM_HARMONIC, note.id(), HARMONIC_GHOST));
                        } else {
                            recovered.add(note);
                        }
                    }
                    since[pitch] = -1;
                }
            }
        }
        return recovered;
    }

    private static boolean harmonicGhost(Note note, Map<Integer, List<ExternalTranscribers.RawNote>> byPitch,
                                         ReconstructionLeadAudio.Track track) {
        for (int i = 0; i < HARMONIC_INTERVALS.length; i++) {
            int lower = note.pitch() - HARMONIC_INTERVALS[i];
            if (lower < ReconstructionLeadAudio.LOW) continue;
            // Decoder gates need not land on the acoustic attack or release.
            var sounding = byPitch.getOrDefault(lower, List.of()).stream()
                    .filter(n -> n.end() + .040 > note.start() && n.start() - .055 < note.end()).toList();
            if (sounding.isEmpty()) continue;
            if (track.followsHarmonic(note, lower, i + 2, frame -> {
                double time = frame * ReconstructionLeadAudio.HOP;
                return sounding.stream().anyMatch(n -> n.start() - .055 <= time && time < n.end() + .040);
            })) return true;
        }
        return false;
    }

    static boolean doubling(Note note, Map<Integer, List<MidiScore.Note>> midi, Spectrum spectrum) {
        if (coverage(note, midi.getOrDefault(note.pitch(), List.of())) >= .70) return true;
        int lower = note.pitch() - 12, upper = note.pitch() + 12;
        if (coverage(note, midi.getOrDefault(lower, List.of())) >= .85
                && spectrum.level(note, note.pitch()) < .5 * spectrum.level(note, lower)) return true;
        return coverage(note, midi.getOrDefault(upper, List.of())) >= .85
                && spectrum.level(note, note.pitch()) < .5 * spectrum.level(note, upper);
    }

    private static double coverage(Note note, List<MidiScore.Note> candidates) {
        double covered = 0, cursor = note.start();
        for (var other : candidates) {
            double start = other.startUs() / 1e6;
            if (start >= note.end()) break;
            double end = Math.min(note.end(), other.endUs() / 1e6);
            covered += Math.max(0, end - Math.max(cursor, start));
            cursor = Math.max(cursor, end);
        }
        return covered / (note.end() - note.start());
    }

    record Spectrum(ReferenceAudio audio) {
        static Spectrum read(Path stem, double duration) throws IOException {
            return new Spectrum(ReconstructionLeadAudio.readMono(stem, duration));
        }

        boolean continuous(Note prior, Note next) {
            int pitch = prior.pitch();
            double boundary = next.start();
            double before = level(pitch, boundary - .025, .012);
            double after = level(pitch, boundary + .025, .012);
            // Preserve a real attack or release, including a re-attack that the
            // decoder wrote without a gap. Sample only this pitch, not the mix.
            if (before < .001 || after < .001 || after > 1.5 * before) return false;
            double from = Math.min(prior.end(), boundary) - .010;
            for (double time = from; time <= boundary + .010; time += .010) {
                if (level(pitch, time, .012) < .65 * Math.min(before, after)) return false;
            }
            return true;
        }

        double level(Note note, int pitch) {
            double total = 0;
            for (double fraction : new double[]{.25, .50, .75}) {
                double time = note.start() + fraction * (note.end() - note.start());
                total += level(pitch, time, .026);
            }
            return total / 3;
        }

        private double level(int pitch, double time, double halfSeconds) {
            int center = (int) Math.round(time * audio.sampleRate());
            int half = (int) (halfSeconds * audio.sampleRate());
            double angle = 2 * Math.PI * ReconstructionLeadAudio.hz(pitch) / audio.sampleRate();
            double real = 0, imaginary = 0;
            for (int i = Math.max(0, center - half); i < Math.min(audio.samples().length, center + half); i++) {
                double offset = i - center;
                double sample = audio.samples()[i] * (.5 + .5 * Math.cos(Math.PI * offset / half));
                real += sample * Math.cos(angle * offset);
                imaginary += sample * Math.sin(angle * offset);
            }
            return Math.hypot(real, imaginary) / half;
        }
    }
}
