package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

final class ReconstructionStages {
    static final int VERSION = 1;
    static final long MICROS_PER_SECOND = 1_000_000;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    record Note(String id, int part, double start, double end, int pitch, int velocity,
                String source, double confidence) {
        MidiScore.Note midi() {
            return new MidiScore.Note(part, pitch, velocity, Math.round(start * MICROS_PER_SECOND),
                    Math.round(end * MICROS_PER_SECOND));
        }
    }

    record Score(int version, long durationUs, int frames, MidiScore source, Map<Integer, Integer> sourceCounts,
                 List<Note> notes, List<S3kReconstruction.Reduction> reductions,
                 List<ReconstructionPitchRefinement.Change> pitchChanges) {}

    record PassThrough(int version, List<Note> notes) {}

    private ReconstructionStages() {}

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length < 4) throw new IllegalArgumentException("reconstruct run expects MIDI WAV INDEX.json WORK_DIR");
        Path midi = Path.of(args[0]), reference = Path.of(args[1]), index = Path.of(args[2]);
        Path work = Path.of(args[3]).toAbsolutePath().normalize();
        var options = ReconstructionCli.parseOptions(
                java.util.stream.Stream.concat(java.util.stream.Stream.of("reconstruct"), java.util.Arrays.stream(args))
                        .toArray(String[]::new), midi.getFileName().toString());
        Score transcription = transcribe(midi, reference, options);
        Files.createDirectory(work);
        write(work.resolve("score.json"), transcription);
        var score = read(work.resolve("score.json"), Score.class);
        var notes = score.notes();
        for (String artifact : List.of("structure.json", "roles.json", "allocation.json")) {
            Path file = work.resolve(artifact);
            write(file, new PassThrough(VERSION, notes));
            notes = read(file, PassThrough.class).notes();
        }
        ReconstructionCli.assemble(midi, reference, index, work, work, options, score, notes, out);
    }

    static Score transcribe(Path midi, Path reference, ReconstructionCli.Options options) throws IOException {
        long sourceDurationUs = ReferenceAudio.durationMicros(reference);
        long durationUs = options.durationUs() == null ? sourceDurationUs : options.durationUs();
        if (durationUs > sourceDurationUs) throw new IllegalArgumentException("--seconds exceeds reference WAV duration");
        long presentations = (durationUs * PRESENTATIONS_PER_SECOND + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND;
        if (presentations < 1 || presentations > S3kRenderer.MAX_PRESENTATIONS)
            throw new IllegalArgumentException("Reconstruction interval must fit in "
                    + S3kRenderer.MAX_PRESENTATIONS + " presentations. Use --seconds for a shorter interval.");
        int frames = (int) presentations;
        MidiScore original = MidiScore.read(midi);
        for (int part : options.stems().keySet()) {
            if (original.parts().stream().noneMatch(p -> p.id() == part))
                throw new IllegalArgumentException("Unknown MIDI part ID for --stem: " + part);
            ReferenceAudio.durationMicros(options.stems().get(part));
        }
        var refinement = options.audioRefine()
                ? ReconstructionPitchRefinement.refine(original, reference, options.stems(), durationUs)
                : new ReconstructionPitchRefinement.Result(original, List.of());
        MidiScore score = refinement.score();
        var prepared = S3kReconstruction.prepare(score, durationUs, options.keepOctaves());
        var counts = new LinkedHashMap<Integer, Integer>();
        var originalEnds = new HashMap<MidiScore.Note, Integer>();
        for (var note : original.notes()) {
            if (note.startUs() >= durationUs) break;
            counts.merge(note.partId(), 1, Integer::sum);
            var clipped = new MidiScore.Note(note.partId(), note.pitch(), note.velocity(), note.startUs(),
                    Math.min(note.endUs(), durationUs));
            originalEnds.merge(clipped, 1, Integer::sum);
        }
        var notes = new ArrayList<Note>();
        for (var note : prepared.notes()) {
            int remaining = originalEnds.getOrDefault(note, 0);
            if (remaining != 0) originalEnds.put(note, remaining - 1);
            notes.add(new Note("n" + (notes.size() + 1), note.partId(), note.startUs() / (double) MICROS_PER_SECOND,
                    note.endUs() / (double) MICROS_PER_SECOND, note.pitch(), note.velocity(),
                    remaining == 0 ? "audio-refine" : "midi", remaining == 0 ? .85 : 1));
        }
        return new Score(VERSION, durationUs, frames, score, counts, notes, prepared.reductions(), refinement.changes());
    }

    static <T> T read(Path file, Class<T> type) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            T value = JSON.fromJson(reader, type);
            if (value == null) throw new IllegalArgumentException(file + ": empty artifact");
            return value;
        } catch (JsonParseException malformed) {
            throw new IllegalArgumentException(file + ": " + malformed.getMessage(), malformed);
        }
    }

    static void write(Path file, Object value) throws IOException {
        Files.writeString(file, JSON.toJson(value) + "\n");
    }
}
