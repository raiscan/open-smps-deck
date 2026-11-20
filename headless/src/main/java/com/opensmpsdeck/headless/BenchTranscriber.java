package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Adapter between the corpus builder and the staged reconstruction transcriber hook. */
public final class BenchTranscriber {
    public record Note(double start, double end, int pitch, int velocity) {}
    public record Result(Map<String, java.util.List<Note>> notes, JsonElement provenance) {}

    private BenchTranscriber() {}

    public static Result collect(Path midi, Map<String, Path> audio, String name, Path config, String device)
            throws IOException {
        var score = MidiScore.read(midi);
        var stems = new TreeMap<Integer, String>();
        var roles = new TreeMap<Integer, String>();
        for (var part : score.parts()) {
            if (!part.drums() && audio.containsKey(part.name())) {
                stems.put(part.id(), audio.get(part.name()).toString());
                roles.put(part.id(), part.name());
            }
        }
        var result = ExternalTranscribers.collect(score, stems, name, config, roles, device, Map.of());
        var notes = new TreeMap<String, java.util.List<Note>>();
        var evidence = new TreeMap<String, ExternalTranscribers.Evidence>();
        result.forEach((id, part) -> {
            String role = roles.get(id);
            notes.put(role, part.notes().stream().map(note -> new Note(
                    note.start(), note.end(), note.pitch(), note.velocity())).toList());
            evidence.put(role, part.provenance());
        });
        return new Result(notes, new Gson().toJsonTree(evidence));
    }
}
