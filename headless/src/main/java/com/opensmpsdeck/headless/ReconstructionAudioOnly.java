package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

final class ReconstructionAudioOnly {
    static final String SOURCE = "audio-only";
    static final String DRUM_SOURCE = "audio-drums";
    static final int DRUM_PART = 0;
    private static final double SILENCE_RMS = .001;

    record Stem(String label, String role, @Nullable String transcriberRole) {}
    record Detector(String name, String version, List<MidiScore.Note> hits, @Nullable Map<String, String> metadata) {}
    record Provenance(String source, @Nullable String transcriber, Detector drumDetector, Map<Integer, Stem> stems,
                      List<String> skippedStems, @Nullable Boolean explicitLead) {}
    record Input(Provenance provenance, Map<Path, String> tonal, Map<Integer, Path> drums, @Nullable Integer leadPart) {}

    private ReconstructionAudioOnly() {}

    static Input prepare(Map<String, Path> stems, Map<String, String> overrides, String transcriber,
                         AudioDrumDetector detector, double seconds, boolean explicitLead) throws IOException {
        if (stems.isEmpty()) throw new IllegalArgumentException("--audio-only needs labelled --stem LABEL=WAV inputs");
        if (!stems.keySet().containsAll(overrides.keySet()))
            throw new IllegalArgumentException("--stem-role must name a supplied --stem label");
        var tonal = new LinkedHashMap<Path, String>();
        var drums = new TreeMap<Integer, Path>();
        var identities = new TreeMap<Integer, Stem>();
        var skipped = new ArrayList<String>();
        var hits = new ArrayList<MidiScore.Note>();
        var paths = new HashSet<Path>();
        AudioDrumDetector.Detection detection = null;
        Integer lead = null;
        int nextPart = 1;
        for (var entry : stems.entrySet()) {
            String label = entry.getKey();
            String role = overrides.getOrDefault(label, defaultRole(label));
            Path path = entry.getValue().toRealPath();
            if (!paths.add(path)) throw new IllegalArgumentException("Stem already supplied: " + path);
            double available = ReferenceAudio.durationMicros(path) / 1e6;
            if (available + .001 < seconds)
                throw new IllegalArgumentException("Stem " + label + " ends before the reconstruction interval");
            if (silent(path, Math.min(seconds, available))) {
                skipped.add(label);
                continue;
            }
            if (role.equals("drums")) {
                if (drums.putIfAbsent(DRUM_PART, path) != null)
                    throw new IllegalArgumentException("Audio-only reconstruction accepts one active drums stem");
                identities.put(DRUM_PART, new Stem(label, role, null));
                detection = detector.detect(path, seconds);
                for (var hit : detection.hits()) {
                    long start = Math.round(hit.seconds() * 1e6);
                    long end = Math.min(Math.round(seconds * 1e6), start + 60_000);
                    if (end > start) hits.add(new MidiScore.Note(DRUM_PART, hit.pitch(), hit.velocity(), start, end));
                }
            } else {
                int part = nextPart++;
                // The arrangement role is lead. Use voice conditioning for the vocals stem.
                String transcriberRole = transcriberRole(label, role);
                identities.put(part, new Stem(label, role, transcriberRole));
                if (lead == null && role.equals("lead")) lead = part;
                tonal.put(path, transcriberRole);
            }
        }
        if (tonal.isEmpty()) throw new IllegalArgumentException("No audible tonal stem to transcribe in the requested interval");
        hits.sort(Comparator.comparingLong(MidiScore.Note::startUs).thenComparingInt(MidiScore.Note::pitch));
        return new Input(new Provenance(SOURCE, transcriber, new Detector(detector.id(), detection == null ? "unused" : detection.version(),
                List.copyOf(hits), detection == null || detection.metadata().isEmpty() ? null : detection.metadata()), identities, List.copyOf(skipped), explicitLead ? true : null), tonal, drums, lead);
    }

    static String label(String value) {
        String label = value.toLowerCase(Locale.ROOT);
        if (!label.matches("[a-z0-9][a-z0-9_-]*"))
            throw new IllegalArgumentException("Expected a stem label using letters, numbers, dashes or underscores: " + value);
        return label;
    }

    static String transcriberRole(String label, String role) {
        return role.equals("lead") && defaultRole(label).equals("lead") && !label.equals("lead") ? "vocals" : role;
    }

    static String defaultRole(String label) {
        return switch (label) {
            case "vocal", "vocals", "voice", "lead" -> "lead";
            case "drum", "drums" -> "drums";
            case "keys", "keyboards" -> "keyboard";
            case "bass", "guitar", "piano", "synth", "keyboard", "strings", "brass", "woodwinds", "backing_vocals" -> label;
            default -> "other";
        };
    }

    static MidiScore drumScore(Provenance provenance) {
        return new MidiScore(provenance.stems.containsKey(DRUM_PART)
                ? List.of(new MidiScore.Part(DRUM_PART, "Drums", 0, true)) : List.of(),
                provenance.drumDetector.hits, List.of());
    }

    static MidiScore.Part part(int id, Stem stem) {
        int program = switch (stem.role) {
            case "lead" -> 80;
            case "piano" -> 0;
            case "backing_vocals" -> 54;
            default -> ReconstructionStemParts.part(id, stem.role).program();
        };
        return new MidiScore.Part(id, stem.label, program, false);
    }

    record Grid(ReconstructionTempo.Estimate tempo, ReconstructionBeatMap beats, List<ReconstructionStructure.Bar> bars) {}

    static Grid grid(ReconstructionStages.Inputs inputs, MidiScore source, List<ReconstructionStages.Note> notes,
                     double seconds) throws IOException {
        var options = inputs.grid();
        if (options.bpm() != null) {
            var tempo = ReconstructionTempo.override(options, 4);
            return new Grid(tempo, ReconstructionBeatMap.constant(tempo.selected(), seconds),
                    ReconstructionTempo.bars(tempo.selected(), seconds));
        }

        // A steady drum grid is usually better evidence than transcribed tonal attacks.
        String drum = inputs.stems().get(DRUM_PART);
        var mixOnsets = ReconstructionRhythmAudio.read(Path.of(inputs.wav()), seconds, false).onsets();
        var onsets = drum == null ? mixOnsets : ReconstructionRhythmAudio.read(Path.of(drum),
                Math.min(seconds, ReferenceAudio.durationMicros(Path.of(drum)) / 1e6), false).onsets();
        var timing = source;
        if (source.notes().size() < 16) {
            var parts = new ArrayList<>(source.parts());
            parts.add(new MidiScore.Part(Integer.MAX_VALUE, "Onsets", 0, false));
            var attacks = new ArrayList<>(source.notes());
            // Supply explicit mix attacks as timing anchors when an isolated kit is unavailable.
            // The beat tracker uses its separate audio argument only to corroborate anchors.
            for (var hit : mixOnsets) {
                long start = Math.round(hit.time() * 1e6);
                attacks.add(new MidiScore.Note(Integer.MAX_VALUE, 60, 80, start, start + 1));
            }
            if (mixOnsets.size() < 16) for (var note : notes) attacks.add(note.midi());
            attacks.sort(Comparator.comparingLong(MidiScore.Note::startUs));
            timing = new MidiScore(parts, attacks, List.of());
        }
        var alternatives = new ArrayList<ReconstructionBeatTracker.Alternative>();
        var beats = ReconstructionBeatTracker.track(timing, seconds, onsets, 4, alternatives);
        var estimate = ReconstructionTempo.estimate(timing, seconds, onsets, 4);
        if (beats != null && beats.confidence() >= ReconstructionTimeMap.MIN_CONFIDENCE) {
            double bpm = beats.openingBpm(), period = 60 / bpm;
            double downbeat = beats.seconds(beats.downbeat());
            var selected = new ReconstructionTempo.Grid(bpm, ReconstructionTempo.modulo(downbeat, period),
                    ReconstructionTempo.modulo(downbeat, 4 * period), 4, beats.confidence());
            var rejected = new ArrayList<ReconstructionTempo.Grid>();
            for (var alternative : alternatives) {
                if (Math.abs(alternative.bpm() - alternatives.getFirst().bpm()) < 4) continue;
                rejected.add(ReconstructionTempo.audioCandidate(timing, onsets, seconds,
                        alternative.bpm(), alternative.score(), 4));
            }
            var tempo = new ReconstructionTempo.Estimate(selected, beats.confidence(), 0, beats.confidence(),
                    List.copyOf(rejected), "tracked");
            return new Grid(tempo, beats, beats.bars(seconds));
        }
        var grid = estimate.selected();
        if (timing.notes().isEmpty()) grid = new ReconstructionTempo.Grid(120, 0, 0, 4, 0);
        var tempo = new ReconstructionTempo.Estimate(grid, estimate.confidence(), 0, estimate.audioEvidence(),
                estimate.alternatives(), "estimated");
        return new Grid(tempo, ReconstructionBeatMap.constant(grid, seconds), ReconstructionTempo.bars(grid, seconds));
    }

    static boolean silent(Path path, double seconds) throws IOException {
        for (double from = 0; from < seconds; from += 1) {
            var audio = ReferenceAudio.read(path, from, Math.min(1, seconds - from));
            double squares = 0;
            int window = Math.max(1, audio.sampleRate() / 10);
            for (int i = 0; i < audio.samples().length; i++) {
                squares += (double) audio.samples()[i] * audio.samples()[i];
                int count = i % window + 1;
                if (count == window || i == audio.samples().length - 1) {
                    if (squares >= SILENCE_RMS * SILENCE_RMS * count) return false;
                    squares = 0;
                }
            }
        }
        return true;
    }
}
