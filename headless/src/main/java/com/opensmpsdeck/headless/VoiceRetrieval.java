package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures how often the per-note decode names the native patch of a note,
 * given a finished reconstruction and the isolated-channel truth. It only
 * reports. Truth labels the notes after the decode has run.
 *
 * <p>Read the numbers with these limits. A note's own best patch is the best
 * of the decode's candidate shortlist, not of the whole bank. Several
 * reconstructed notes can be labelled from one native note. Exposure is
 * computed from the native score, so it describes the truth and not what the
 * decode saw.
 */
public final class VoiceRetrieval {
    /** Onset tolerance for pairing a reconstructed note with a native note of the same pitch. */
    static final double MATCH_SECONDS = .05;
    /** Below this share of harmonics clear of other native channels, a note counts as lower exposure. */
    static final double EXPOSED = .1;

    /** A native tone. {@code hash} is the FM patch hash, or null for a PSG tone. */
    public record Truth(String lane, double start, double end, int pitch, String hash) {}

    /**
     * Counts over notes whose native patch is known. {@code top1} takes each note's
     * own best candidate and {@code decoded} the decoded label. Notes of lower truth
     * exposure are counted apart and are not in the first three numbers.
     */
    public record Counts(int notes, int top1, int decoded, int lowerExposure, int lowerExposureTop1,
                         int lowerExposureDecoded) {
        Counts plus(boolean exposed, boolean first, boolean label) {
            return exposed ? new Counts(notes + 1, top1 + (first ? 1 : 0), decoded + (label ? 1 : 0),
                    lowerExposure, lowerExposureTop1, lowerExposureDecoded)
                    : new Counts(notes, top1, decoded, lowerExposure + 1, lowerExposureTop1 + (first ? 1 : 0),
                    lowerExposureDecoded + (label ? 1 : 0));
        }
    }

    /**
     * {@code reconstructed} scores the reconstruction's own notes that pair with one native
     * patch, and {@code unlabelled} counts its decoded notes that pair with none or with two.
     * {@code nativeNotes} is an oracle: the native notes themselves, with their true timing
     * and channel grouping, on the stem their channel was mostly heard in. {@code unplaced}
     * counts native notes of channels that pair with no reconstructed note.
     */
    public record Result(Counts reconstructed, int unlabelled, Counts nativeNotes, int unplaced) {}

    private VoiceRetrieval() {}

    public static Result measure(Path work, List<Truth> truth) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var voicing = Files.isRegularFile(work.resolve("voicing.json"))
                ? ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class) : null;
        var catalogue = InstrumentCatalogue.openReconstruction(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
        var decoder = new VoiceNoteDecode(catalogue, score.inputs().targetGame());
        var parts = parts(score, allocation);
        var plan = ReconstructionVoicePlan.saved(work);
        var voices = plan == null ? Map.<String, ReconstructionVoicePlan.Voice>of() : plan.byNote();

        var exposure = exposure(truth);
        var fm = truth.stream().filter(note -> note.hash() != null).toList();
        var votes = new HashMap<String, Map<Integer, Integer>>();
        var reconstructed = new Counts(0, 0, 0, 0, 0, 0);
        int unlabelled = 0;
        for (var part : parts.entrySet()) {
            // A stem can hold several voices. Each is decoded on its own.
            var groups = new LinkedHashMap<String, List<VoiceNoteDecode.Note>>();
            for (var note : part.getValue()) {
                var voice = voices.get(note.id());
                groups.computeIfAbsent(voice == null ? "" : voice.id(), ignored -> new ArrayList<>()).add(note);
            }
            for (var group : groups.values()) {
            var decoded = decoder.decode(group, stem(score, part.getKey()), home(voicing, part.getKey()), false);
            for (int i = 0; i < decoded.notes().size(); i++) {
                var note = decoded.notes().get(i).note();
                Truth match = null;
                boolean single = true;
                for (var candidate : fm) {
                    if (candidate.pitch() != note.pitch() || Math.abs(candidate.start() - note.start()) >= MATCH_SECONDS) continue;
                    if (match != null && !match.hash().equals(candidate.hash())) single = false;
                    if (match == null) match = candidate;
                }
                if (match == null || !single) {
                    unlabelled++;
                    continue;
                }
                votes.computeIfAbsent(match.lane(), ignored -> new HashMap<>()).merge(part.getKey(), 1, Integer::sum);
                reconstructed = reconstructed.plus(exposure.get(match) >= EXPOSED,
                        right(catalogue, decoded.candidates().get(best(decoded.notes().get(i))), match),
                        right(catalogue, decoded.candidates().get(decoded.labels()[i]), match));
            }
            }
        }

        // Each native channel is scored on the stem most of its paired notes were heard in.
        var placed = new LinkedHashMap<String, Integer>();
        var lanes = new LinkedHashMap<String, List<VoiceNoteDecode.Note>>();
        var truths = new HashMap<String, Truth>();
        int unplaced = 0, serial = 0;
        for (var note : truth) {
            String id = "truth-" + serial++;
            truths.put(id, note);
            lanes.computeIfAbsent(note.lane(), ignored -> new ArrayList<>())
                    .add(new VoiceNoteDecode.Note(id, note.start(), note.end(), note.pitch()));
        }
        for (var lane : votes.entrySet())
            placed.put(lane.getKey(), lane.getValue().entrySet().stream()
                    .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .orElseThrow().getKey());
        var nativeNotes = new Counts(0, 0, 0, 0, 0, 0);
        for (var lane : lanes.entrySet()) {
            if (truths.get(lane.getValue().getFirst().id()).hash() == null) continue;
            Integer part = placed.get(lane.getKey());
            if (part == null) {
                unplaced += lane.getValue().size();
                continue;
            }
            var decoded = decoder.decode(lane.getValue(), stem(score, part), home(voicing, part), false);
            for (int i = 0; i < decoded.notes().size(); i++) {
                var match = truths.get(decoded.notes().get(i).note().id());
                nativeNotes = nativeNotes.plus(exposure.get(match) >= EXPOSED,
                        right(catalogue, decoded.candidates().get(best(decoded.notes().get(i))), match),
                        right(catalogue, decoded.candidates().get(decoded.labels()[i]), match));
            }
        }
        return new Result(reconstructed, unlabelled, nativeNotes, unplaced);
    }

    /** Stem-backed tonal parts and every note of each, in source seconds and time order. */
    static Map<Integer, List<VoiceNoteDecode.Note>> parts(ReconstructionStages.Score score,
                                                           ReconstructionAllocation.Result allocation) {
        var time = ReconstructionTimeMap.of(score);
        var parts = new LinkedHashMap<Integer, List<VoiceNoteDecode.Note>>();
        for (var part : score.source().parts()) {
            String stem = score.inputs().stems().get(part.id());
            if (part.drums() || stem == null || stem.equals(score.inputs().wav())) continue;
            var notes = new ArrayList<VoiceNoteDecode.Note>();
            for (var entry : allocation.notes()) {
                var note = entry.note();
                if (note.part() != part.id() || entry.role() == ReconstructionRoles.Role.drums) continue;
                if (allocation.timeBase() == ReconstructionTimeMap.TimeBase.project) note = time.sourceNote(note);
                notes.add(new VoiceNoteDecode.Note(note.id(), note.start(), note.end(), note.pitch()));
            }
            notes.sort(Comparator.comparingDouble(VoiceNoteDecode.Note::start).thenComparing(VoiceNoteDecode.Note::id));
            if (!notes.isEmpty()) parts.put(part.id(), notes);
        }
        return parts;
    }

    private static Path stem(ReconstructionStages.Score score, int part) {
        return Path.of(score.inputs().stems().get(part));
    }

    private static List<String> home(ReconstructionVoicing.Result voicing, int part) {
        if (voicing == null || voicing.sourceInstruments() == null) return List.of();
        var sound = voicing.sourceInstruments().get(ReconstructionVoicePlan.SourceInstrument.id(part));
        return sound == null || sound.fm().variantId() == null ? List.of() : List.of(sound.fm().variantId());
    }

    private static int best(VoiceNoteDecode.Scored note) {
        int best = 0;
        for (int k = 1; k < note.scores().length; k++) if (note.scores()[k] > note.scores()[best]) best = k;
        return best;
    }

    private static boolean right(InstrumentCatalogue catalogue, String variant, Truth truth) {
        return catalogue.match(variant).variant().assetId().equals("fm-voice-" + truth.hash());
    }

    /**
     * For each native note, the share of its own harmonic bins that no other channel's
     * harmonics touch while it sounds. It is computed from the native score alone.
     */
    static Map<Truth, Double> exposure(List<Truth> truth) {
        var result = new java.util.IdentityHashMap<Truth, Double>();
        var ordered = truth.stream().sorted(Comparator.comparingDouble(Truth::start)).toList();
        for (var note : truth) {
            var own = VoiceNoteDecode.comb(note.pitch(), 2, 1.5);
            double end = Math.min(note.end(), note.start() + .5), kept = 0, total = 0;
            for (double at = note.start(); at < end || at == note.start(); at += .008) {
                var covered = new boolean[own.length];
                for (var other : ordered) {
                    if (other.start() >= at + .043) break;
                    if (other == note || other.lane().equals(note.lane()) || other.end() + .033 <= at) continue;
                    var comb = VoiceNoteDecode.comb(other.pitch(), 1, 1.5);
                    for (int bin = 0; bin < comb.length; bin++) if (comb[bin] > 0) covered[bin] = true;
                }
                for (int bin = 0; bin < own.length; bin++) {
                    total += own[bin];
                    if (!covered[bin]) kept += own[bin];
                }
            }
            result.put(note, total > 0 ? kept / total : 0);
        }
        return result;
    }
}
