package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Replaces a source span with monophonic F0 evidence while preserving polyphony around it. */
record ReconstructionMonophonicStem(String adapter, @Nullable List<Double> span, @Nullable Integer registerShift) {
    static final String KEY = "monophonicStem";
    static final com.google.gson.Gson JSON = new com.google.gson.Gson();

    record Snapshot(ReconstructionMonophonicStem rule, int polyphonicCount, ExternalTranscribers.Evidence contour) {}

    void validate(Map<String, List<String>> adapters) {
        require(adapter != null && adapters.containsKey(adapter), KEY, "expected a configured F0 adapter");
        ReconstructionArrangement.span(span);
        require(registerShift == null || Math.abs((long) registerShift) <= 48, KEY,
                "registerShift must be between -48 and 48 semitones");
    }

    ExternalTranscribers.PartNotes snapshot(ExternalTranscribers.PartNotes poly, ExternalTranscribers.PartNotes mono) {
        require(mono.provenance().metadata().containsKey("f0Cents10ms"), KEY, "adapter must return f0Cents10ms");
        var notes = new ArrayList<ExternalTranscribers.RawNote>();
        if (poly != null) notes.addAll(poly.notes());
        int split = notes.size();
        notes.addAll(mono.notes());
        var source = poly == null ? mono.provenance() : poly.provenance();
        var metadata = poly == null ? new TreeMap<String, String>() : new TreeMap<>(source.metadata());
        metadata.put(KEY, JSON.toJson(new Snapshot(this, split, mono.provenance())));
        return new ExternalTranscribers.PartNotes(new ExternalTranscribers.Evidence(source.tool(),
                source.role(), source.device(), source.inputSha256(), source.outputSha256(), source.configSha256(),
                metadata, notes.size(), true), List.copyOf(notes));
    }

    static List<Note> join(int part, ExternalTranscribers.PartNotes imported,
                           ReconstructionLeadAudio.Track track, ReconstructionStemParts.Spectrum spectrum,
                           double duration, List<S3kReconstruction.Reduction> reductions) {
        var snapshot = JSON.fromJson(imported.provenance().metadata().get(KEY), Snapshot.class);
        int split = snapshot.polyphonicCount();
        require(split >= 0 && split <= imported.notes().size(), KEY, "invalid saved polyphonic note count");
        var rule = snapshot.rule();
        double from = rule.span == null ? 0 : rule.span.getFirst();
        double to = rule.span == null ? duration : Math.min(duration, rule.span.getLast());
        var result = new ArrayList<Note>();
        if (split > 0) {
            var raw = imported.notes().subList(0, split);
            var poly = ReconstructionStemParts.notes(part, raw, duration, true);
            poly.addAll(ReconstructionStemParts.upperVoicing(part, raw, spectrum, duration, reductions));
            var outside = new ArrayList<Note>();
            for (var note : poly) {
                if (note.end() <= from || note.start() >= to || from >= to) {
                    outside.add(note);
                } else {
                    if (note.start() < from) outside.add(note.withTiming(note.start(), from));
                    if (note.end() > to) outside.add(copy(note, note.id() + "-after-mono", to, note.end()));
                }
            }
            result.addAll(ReconstructionStemParts.joinHolds(outside, spectrum, reductions));
        }
        if (from >= to) return List.copyOf(result);
        var evidence = snapshot.contour();
        var metadata = new TreeMap<>(evidence.metadata());
        metadata.put("vocalRegisterShift", Integer.toString(rule.registerShift == null ? 0 : rule.registerShift));
        var source = new ExternalTranscribers.Evidence(evidence.tool(), evidence.role(), evidence.device(),
                evidence.inputSha256(), evidence.outputSha256(), evidence.configSha256(), metadata, evidence.noteCount(), true);
        var mono = new ExternalTranscribers.PartNotes(source, imported.notes().subList(split, imported.notes().size()));
        var notes = ReconstructionVocalLead.select(part, mono, track, duration).notes();
        double cursor = from;
        for (var note : notes.stream().sorted(ReconstructionStages.NOTE_ORDER).toList()) {
            double start = Math.max(cursor, note.start()), end = Math.min(to, note.end());
            if (start >= end) continue;
            result.add(copy(note, "mono-" + note.id(), start, end));
            cursor = end;
        }
        result.sort(Comparator.comparingDouble(Note::start).thenComparing(Note::id));
        return List.copyOf(result);
    }

    private static Note copy(Note note, String id, double start, double end) {
        // The stem may have been clipped mid-note. Downstream motion analysis
        // should use the final gate and the part's own source rather than the old gate.
        return new Note(id, note.part(), start, end, note.pitch(), note.velocity(), note.source(), note.confidence());
    }
}
