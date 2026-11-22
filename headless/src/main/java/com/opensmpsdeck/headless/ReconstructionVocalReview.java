package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

/** Review source-time vocal attacks against the captured model evidence. */
public final class ReconstructionVocalReview {
    private static final Gson JSON = new Gson();
    private static final double ONSET_TOLERANCE = .05;
    private static final double PITCH_TOLERANCE = .7;
    private static final String GROUP = "vocal-lead-review";

    private ReconstructionVocalReview() {}

    record Contour(Double medianMidi, Double slopeSemitonesPerSecond, Double vibratoRmsSemitones,
                   double pitchConfidence, double voicingFraction) {}
    record LeadNote(String noteId, double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                    int pitch, Double syllableOnset, List<String> onsetEvidence, Contour contour) {}
    record Flag(String type, double sourceTime, JsonObject proposal) {}
    record Span(String id, double start, double end) {}
    record Phrase(String id, double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                  List<LeadNote> notes, List<Flag> flags) {}
    record Boundaries(int[] syllable, int[] voicing, int[] pitchChange) {}

    static void cli(String[] args, PrintStream out) throws IOException {
        require(args.length > 0, "vocal-review", "expected WORK_DIR [--phrase ID] [--propose]");
        String phrase = null;
        boolean propose = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--phrase" -> {
                    require(phrase == null && i + 1 < args.length, "vocal-review", "expected one phrase ID");
                    phrase = args[++i];
                }
                case "--propose" -> propose = true;
                default -> throw new IllegalArgumentException("vocal-review: unknown option " + args[i]);
            }
        }
        out.println(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(review(Path.of(args[0]), phrase, propose)));
    }

    public static JsonObject review(Path work, String phraseId, boolean saveProposals) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var inputs = score.inputs();
        Integer part = inputs == null ? null : inputs.leadPart();
        require(part != null && ReconstructionVocalLead.isVocal(inputs, part),
                "vocal-review", "the reconstruction has no selected vocal lead");
        var imported = inputs.externalNotes() == null ? null : inputs.externalNotes().get(part);
        require(imported != null, "vocal-review", "the selected lead has no transcription evidence");
        var metadata = imported.provenance().metadata();
        var evidence = syllableEvidence(imported.provenance());
        require(evidence != null, "vocal-review", "the selected lead has no RMVPE syllable snapshot");
        int shift = Integer.parseInt(metadata.getOrDefault("vocalRegisterShift", "0"));
        var contour = ByteBuffer.wrap(Base64.getDecoder().decode(evidence.get("f0Cents10ms")))
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        double[] pitches = new double[contour.remaining()];
        for (int i = 0; i < pitches.length; i++) {
            int cents = Short.toUnsignedInt(contour.get(i));
            if (cents > 0) pitches[i] = cents / 100.0 + shift;
        }
        var boundaries = JSON.fromJson(evidence.get("vocalBoundaries10ms"), Boundaries.class);
        var time = ReconstructionTimeMap.of(score);
        var notes = score.notes().stream().filter(note -> note.part() == part)
                .sorted(ReconstructionStages.NOTE_ORDER).toList();

        var spans = new ArrayList<Span>();
        double start = 0;
        var sections = ReconstructionSections.load(work, score).sections().stream()
                .filter(section -> section.level() == ReconstructionSections.Level.phrase)
                .sorted(Comparator.comparingDouble(ReconstructionSections.Section::sourceStart)).toList();
        for (var section : sections) {
            if (section.sourceStart() > start)
                spans.add(new Span("unphrased-" + spans.size(), start, section.sourceStart()));
            spans.add(new Span(section.id(), section.sourceStart(), section.sourceEnd()));
            start = section.sourceEnd();
        }
        if (start < time.sourceDuration())
            spans.add(new Span("unphrased-" + spans.size(), start, time.sourceDuration()));
        require(phraseId == null || spans.stream().anyMatch(span -> span.id.equals(phraseId)),
                "phrase", "unknown phrase ID " + phraseId);

        var phrases = new ArrayList<Phrase>();
        var counts = new TreeMap<>(Map.of("octaveJump", 0, "contourDisagreement", 0,
                "shortBetweenSamePitch", 0, "missingSyllableNote", 0, "noOnsetEvidence", 0, "outsidePhraseRange", 0));
        var edits = new com.google.gson.JsonArray();
        for (var span : spans) {
            if (phraseId != null && !span.id.equals(phraseId)) continue;
            var phrase = analyze(part, span, notes, pitches, boundaries, time);
            if (phrase.notes.isEmpty() && phrase.flags.isEmpty()) continue;
            phrases.add(phrase);
            for (var flag : phrase.flags) {
                counts.merge(flag.type, 1, Integer::sum);
                edits.add(flag.proposal);
            }
        }
        int saved = 0;
        if (saveProposals && !edits.isEmpty()) {
            var existing = ReconstructionEdits.read(work).intents().stream().map(ReconstructionEdits.Intent::id)
                    .collect(java.util.stream.Collectors.toSet());
            var pending = new com.google.gson.JsonArray();
            for (var edit : edits)
                if (!existing.contains(edit.getAsJsonObject().get("id").getAsString())) pending.add(edit);
            if (!pending.isEmpty()) {
                ReconstructionEditCommand.run(work, "propose", pending, null, false, (a, b, c) -> {});
                saved = pending.size();
            }
        }
        return JSON.toJsonTree(Map.of("part", part, "registerShift", shift,
                "stem", inputs.stems().get(part), "flagsByType", counts, "phrases", phrases,
                "proposalsSaved", saved, "edits", edits)).getAsJsonObject();
    }

    private static Map<String, String> syllableEvidence(ExternalTranscribers.Evidence provenance) {
        var meta = provenance.metadata();
        if (meta.containsKey("f0Cents10ms") && meta.containsKey("vocalBoundaries10ms")) return meta;
        if (meta.containsKey("vocalCandidates")) {
            for (var candidate : JSON.fromJson(meta.get("vocalCandidates"), ExternalTranscribers.Evidence[].class)) {
                var found = syllableEvidence(candidate);
                if (found != null) return found;
            }
        }
        return null;
    }

    static Phrase analyze(int part, Span span, List<Note> all, double[] f0, Boundaries boundaries,
                          ReconstructionTimeMap time) {
        var notes = all.stream().filter(note -> note.start() >= span.start && note.start() < span.end).toList();
        var review = new ArrayList<LeadNote>();
        var flags = new ArrayList<Flag>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var before = i == 0 ? null : notes.get(i - 1);
            var after = i + 1 == notes.size() ? null : notes.get(i + 1);
            var contour = summarize(f0, note.start(), note.end(), note.pitch());
            var onset = nearest(boundaries.syllable, note.start(), ONSET_TOLERANCE);
            var reasons = new ArrayList<String>();
            if (onset != null) reasons.add("syllable");
            if (nearest(boundaries.voicing, note.start(), .02) != null) reasons.add("voicing");
            if (nearest(boundaries.pitchChange, note.start(), .02) != null) reasons.add("pitchChange");
            review.add(new LeadNote(note.id(), note.start(), note.end(), time.project(note.start()),
                    time.project(note.end()), note.pitch(), onset, reasons, contour));

            var target = Map.<String, Object>of("noteId", note.id());
            if (before != null && after != null && note.start() - before.end() <= .25 && after.start() - note.end() <= .25) {
                int folded = note.pitch() + (note.pitch() > before.pitch() ? -12 : 12);
                if (Math.abs(note.pitch() - before.pitch()) >= 10 && Math.abs(note.pitch() - after.pitch()) >= 10
                        && Math.abs(folded - before.pitch()) <= 2 && Math.abs(folded - after.pitch()) <= 2)
                    flags.add(flag(part, note.start(), "octaveJump", "Fold isolated octave excursion toward both neighbours",
                            "setNote", Map.of("noteId", note.id(), "pitch", folded)));
                if (note.end() - note.start() < .12 && before.pitch() == after.pitch()) {
                    if (before.pitch() == note.pitch() && after.start() - before.end() - (note.end() - note.start()) <= .06)
                        flags.add(flag(part, note.start(), "shortBetweenSamePitch", "Join three adjacent same-pitch gates",
                                "mergeNotes", Map.of("noteIds", List.of(before.id(), note.id(), after.id()))));
                    else
                        flags.add(flag(part, note.start(), "shortBetweenSamePitch", "Review a short interruption between matching neighbours",
                                "deleteNote", target));
                }
            }
            if (contour.medianMidi != null && Math.abs(contour.medianMidi - note.pitch()) > PITCH_TOLERANCE) {
                var curve = bend(f0, note);
                flags.add(curve.isEmpty()
                        ? flag(part, note.start(), "contourDisagreement", "Use the rounded RMVPE median for this gate",
                                "setNote", Map.of("noteId", note.id(), "pitch", playable(contour.medianMidi)))
                        : flag(part, note.start(), "contourDisagreement", "Follow a supported pitch excursion inside this gate",
                                "bend", Map.of("noteId", note.id(), "points", curve)));
            }
            if (reasons.isEmpty())
                flags.add(flag(part, note.start(), "noOnsetEvidence", "Review a gate without a captured syllable, voicing or pitch-change boundary",
                        "deleteNote", target));
            var range = notes.stream().filter(other -> other != note).mapToInt(Note::pitch).sorted().toArray();
            if (range.length >= 4) {
                int margin = range.length / 10;
                int low = range[margin], high = range[range.length - 1 - margin];
                if (note.pitch() < low - 2 || note.pitch() > high + 2) {
                    int folded = note.pitch() + (note.pitch() > high ? -12 : 12);
                    int pitch = folded >= low && folded <= high ? folded : Math.clamp(note.pitch(), low, high);
                    flags.add(flag(part, note.start(), "outsidePhraseRange", "Bring an isolated pitch into the surrounding phrase range",
                            "setNote", Map.of("noteId", note.id(), "pitch", pitch)));
                }
            }
        }
        for (int i = 0; i < boundaries.syllable.length; i++) {
            int frame = boundaries.syllable[i];
            double onset = frame / 100.0;
            if (onset < span.start || onset >= span.end) continue;
            if (all.stream().anyMatch(note -> Math.abs(note.start() - onset) <= ONSET_TOLERANCE)) continue;
            var sounding = all.stream().filter(note -> note.start() < onset && note.end() > onset).findFirst();
            if (sounding.isPresent() && canSplit(sounding.get(), onset, time)) {
                var note = sounding.get();
                var contour = summarize(f0, onset, note.end(), note.pitch());
                int pitch = contour.medianMidi == null ? note.pitch() : playable(contour.medianMidi);
                flags.add(flag(part, onset, "missingSyllableNote", "Retrigger the held gate at the captured syllable onset",
                        "splitNote", Map.of("noteId", note.id(), "at", onset, "pitch", pitch)));
                continue;
            }
            double next = all.stream().filter(note -> note.start() > onset).mapToDouble(Note::start).findFirst()
                    .orElse(time.sourceDuration());
            if (i + 1 < boundaries.syllable.length) next = Math.min(next, boundaries.syllable[i + 1] / 100.0);
            int endFrame = voicedEnd(f0, frame);
            double end = Math.min(next, Math.max(onset + .09, endFrame / 100.0));
            var contour = summarize(f0, onset, Math.max(end, Math.min(time.sourceDuration(), onset + .06)), 0);
            if (end > onset && contour.medianMidi != null)
                flags.add(flag(part, onset, "missingSyllableNote", "Replace the missing attack using the following voiced frames",
                        "insertNote", Map.of("part", part, "start", onset, "end", end, "pitch", playable(contour.medianMidi),
                                "velocity", 96, "role", "melody", "lane", "FM1")));
        }
        flags.sort(Comparator.comparingDouble(Flag::sourceTime).thenComparing(Flag::type));
        return new Phrase(span.id, span.start, span.end, time.project(span.start), time.project(span.end), review, flags);
    }

    private static boolean canSplit(Note note, double onset, ReconstructionTimeMap time) {
        if (note.run() != null || note.bend() != null && note.bend().editReason() != null) return false;
        var gate = time.projectNote(note);
        int tick = time.startTick(time.projectNote(note.withTiming(onset, note.end())));
        return tick > time.startTick(gate) && tick < time.endTick(gate);
    }

    private static int voicedEnd(double[] f0, int start) {
        int last = start - 1;
        for (int frame = start; frame < f0.length && frame - last <= 7; frame++)
            if (f0[frame] > 0) last = frame;
        return last + 1;
    }

    private static List<List<Double>> bend(double[] f0, Note note) {
        if (note.end() - note.start() < .18 || note.run() != null) return List.of();
        int start = frame(note.start(), f0.length), stop = frame(note.end(), f0.length);
        var points = new ArrayList<List<Double>>();
        for (int i = start; i < stop; i += Math.max(3, (stop - start) / 200)) {
            double offset = f0[i] - note.pitch();
            if (f0[i] < 12 || f0[i] > 106 || Math.abs(offset) > 1.5) return List.of();
            points.add(List.of(Math.max(note.start(), i / 100.0), offset));
        }
        return points.size() >= 2 && Math.abs(points.getFirst().get(1)) <= .5 ? points : List.of();
    }

    private static Contour summarize(double[] f0, double start, double end, int pitch) {
        int from = frame(start, f0.length), to = frame(end, f0.length);
        var voiced = IntStream.range(from, to).filter(i -> f0[i] > 0).toArray();
        if (voiced.length == 0) return new Contour(null, null, null, 0, 0);
        var values = Arrays.stream(voiced).mapToDouble(i -> f0[i]).sorted().toArray();
        double median = (values[values.length / 2] + values[(values.length - 1) / 2]) / 2;
        double mean = Arrays.stream(values).average().orElseThrow();
        double mid = Arrays.stream(voiced).average().orElseThrow();
        double covariance = 0, variance = 0;
        int inTune = 0;
        for (int frame : voiced) {
            double dt = (frame - mid) / 100;
            covariance += dt * (f0[frame] - mean);
            variance += dt * dt;
            if (Math.abs(f0[frame] - pitch) <= PITCH_TOLERANCE) inTune++;
        }
        double slope = variance > 0 ? covariance / variance : 0;
        double vibrato = Math.sqrt(Arrays.stream(voiced)
                .mapToDouble(i -> Math.pow(f0[i] - mean - slope * (i - mid) / 100, 2)).average().orElseThrow());
        return new Contour(median, slope, vibrato, inTune / (double) voiced.length, voiced.length / (double) (to - from));
    }

    private static int frame(double seconds, int length) {
        return Math.clamp((int) Math.ceil(seconds * 100 - 1e-9), 0, length);
    }

    private static Double nearest(int[] frames, double seconds, double tolerance) {
        if (frames.length == 0) return null;
        int right = Arrays.binarySearch(frames, (int) Math.ceil(seconds * 100 - 1e-9));
        if (right < 0) right = -right - 1;
        double a = frames[Math.max(0, right - 1)] / 100.0;
        double b = frames[Math.min(right, frames.length - 1)] / 100.0;
        double nearest = Math.abs(seconds - a) <= Math.abs(seconds - b) ? a : b;
        return Math.abs(nearest - seconds) <= tolerance + 1e-9 ? nearest : null;
    }

    private static int playable(double pitch) {
        return Math.clamp((int) Math.round(pitch), 12, 106);
    }

    private static Flag flag(int part, double onset, String type, String reason, String op, Map<String, Object> fields) {
        var entry = JSON.toJsonTree(fields).getAsJsonObject();
        String target = entry.has("noteId") ? "-" + Integer.toUnsignedString(entry.get("noteId").getAsString().hashCode()) : "";
        entry.addProperty("id", "vocal-" + part + "-" + Math.round(onset * 1_000_000) + "-" + type + target);
        entry.addProperty("reason", reason);
        entry.addProperty("op", op);
        entry.addProperty("group", GROUP);
        entry.addProperty("timeBase", "source");
        entry.add("evidence", JSON.toJsonTree(Map.of("path", "score.json", "text", reason)));
        return new Flag(type, onset, ReconstructionProposals.proposed(entry));
    }
}
