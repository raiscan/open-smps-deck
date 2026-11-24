package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetProfile;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/**
 * Two diagnostic measurements of a part's stem, made only on request by
 * {@code reconstruct voice-regime}. Nothing in a reconstruction reads them.
 *
 * <p>The residual is the energy the best patch of the part's fitted shortlist
 * leaves over. A low residual does not show that the bank explains the source,
 * only that one shortlisted patch comes close on the sampled notes. The
 * off-harmonic share says how much energy lies away from the harmonics of the
 * part's own notes. It cannot name an instrument, and a high share can come
 * from other parts in the stem as well as from an acoustic source. The labels
 * on both are tentative.
 */
final class VoiceRegime {
    /** A note must sound this long to be measured, and this many notes make a reading. */
    private static final double MIN_SECONDS = .12;
    private static final int MIN_NOTES = 20, SAMPLE = 60, PITCHES = 8;
    /** Notes below G3 have harmonics too close together to tell on-harmonic energy from the rest. */
    static final int LOWEST_KIND_PITCH = 55;
    private static final double KIND_COMB_WIDTH = 1;
    /** At most this share of a note's energy may be left unexplained by the best stock patch. */
    static final double ADEQUATE_UNEXPLAINED = .4;
    /**
     * Off-harmonic share at or below which a part reads as synthetic, and above which it
     * reads as acoustic. Between them the reading is uncertain. The pair is the 5th and
     * 95th percentile of the best single cut over 200 splits of 15 labelled songs into a
     * fitting half and a held-out half. Held out, one cut is right for about 7 parts in 10.
     */
    static final double SYNTHETIC_OFF_HARMONIC = .14, ACOUSTIC_OFF_HARMONIC = .24;

    /**
     * {@code offHarmonic} is the median share of energy away from the harmonics of the
     * part's own sounding notes, over {@code notes} notes at or above G3. {@code kind} is
     * {@code synthetic}, {@code acoustic}, {@code uncertain} between the two cuts, or
     * {@code undetermined} for a bass part or one with too few such notes.
     */
    record Kind(int notes, @Nullable Double offHarmonic, String kind) {}

    /**
     * {@code unexplained} is the median share of a note's energy the best candidate patch
     * leaves over, with one gain per note. {@code fit} is {@code adequate} or
     * {@code inadequate}, or {@code unmeasured} with too few notes.
     */
    record Reading(int notes, @Nullable Double unexplained, String fit, Kind kind) {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("reconstruct voice-regime expects WORK_DIR");
        out.println(new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(report(Path.of(args[0]))));
    }

    private VoiceRegime() {}

    /** The instrument-kind reading. It needs only the stem and the part's notes. */
    static Kind kind(VoiceNoteDecode decoder, List<VoiceNoteDecode.Note> notes, Path stem) throws IOException {
        return kind(decoder, notes, VoiceNoteDecode.read(stem));
    }

    private static Kind kind(VoiceNoteDecode decoder, List<VoiceNoteDecode.Note> notes, float[] mono) {
        var shares = new ArrayList<Double>();
        var pitches = notes.stream().mapToInt(VoiceNoteDecode.Note::pitch).sorted().toArray();
        boolean bass = pitches.length > 0 && pitches[pitches.length / 2] < LOWEST_KIND_PITCH;
        for (var note : sample(notes, LOWEST_KIND_PITCH)) {
            int start = (int) Math.round(note.start() * VoiceNoteDecode.RATE);
            int frames = VoiceNoteDecode.frames(note, mono.length);
            if (frames == 0) continue;
            var heard = decoder.magnitudes(mono, start, frames);
            double energy = 0, off = 0;
            // The part's own notes sounding in this window are what a pitched source should explain.
            var harmonic = new boolean[heard[0].length];
            double from = note.start(), to = note.start() + frames * .008 + .043;
            for (var other : notes) {
                if (other.start() >= to || other.end() <= from) continue;
                var comb = VoiceNoteDecode.comb(other.pitch(), 1, KIND_COMB_WIDTH);
                for (int bin = 0; bin < comb.length; bin++) if (comb[bin] > 0) harmonic[bin] = true;
            }
            for (var frame : heard) for (int bin = 0; bin < frame.length; bin++) {
                double power = frame[bin] * (double) frame[bin];
                energy += power;
                if (bin >= 3 && !harmonic[bin]) off += power;
            }
            if (energy > 1e-8) shares.add(off / energy);
        }
        if (bass || shares.size() < MIN_NOTES) return new Kind(shares.size(), shares.isEmpty() ? null : median(shares), "undetermined");
        double share = median(shares);
        return new Kind(shares.size(), share, share > ACOUSTIC_OFF_HARMONIC ? "acoustic"
                : share <= SYNTHETIC_OFF_HARMONIC ? "synthetic" : "uncertain");
    }

    /** Both readings. {@code candidates} are the stock patches the part's fit could have chosen. */
    static Reading measure(VoiceNoteDecode decoder, InstrumentCatalogue catalogue, com.opensmpsdeck.project.TargetGame target,
                           List<VoiceNoteDecode.Note> notes, Path stem, List<String> candidates) throws IOException {
        // The stem is read once for both measurements.
        var mono = VoiceNoteDecode.read(stem);
        var kind = kind(decoder, notes, mono);
        var range = TargetProfile.of(target).fm();
        var variants = new ArrayList<InstrumentCatalogue.Variant>();
        for (String id : new LinkedHashSet<>(candidates)) variants.add(catalogue.match(id, target).variant());
        var left = new ArrayList<Double>();
        var pitches = new LinkedHashSet<Integer>();
        for (var note : sample(notes, 0)) {
            int pitch = range.clamp(note.pitch());
            // Each new pitch costs a render of every candidate.
            if (!pitches.contains(pitch) && pitches.size() == PITCHES) continue;
            int start = (int) Math.round(note.start() * VoiceNoteDecode.RATE);
            int frames = VoiceNoteDecode.frames(note, mono.length);
            if (frames == 0 || variants.isEmpty()) continue;
            var heard = decoder.magnitudes(mono, start, frames);
            double energy = 0;
            for (var frame : heard) for (float value : frame) energy += value * (double) value;
            if (energy <= 1e-8) continue;
            pitches.add(pitch);
            var target48 = VoiceNoteDecode.mel(heard);
            float[][] best = null;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (var variant : variants) {
                var template = decoder.magnitudeTemplate(variant, pitch);
                float score = VoiceNoteDecode.cosine(target48, VoiceNoteDecode.mel(template), 0, frames);
                if (score > bestScore) {
                    bestScore = score;
                    best = template;
                }
            }
            double cross = 0, norm = 0;
            int shared = Math.min(frames, best.length);
            for (int frame = 0; frame < shared; frame++) for (int bin = 0; bin < heard[frame].length; bin++) {
                cross += best[frame][bin] * (double) heard[frame][bin];
                norm += best[frame][bin] * (double) best[frame][bin];
            }
            double gain = norm > 0 ? cross / norm : 0, residual = 0;
            for (int frame = 0; frame < frames; frame++) for (int bin = 0; bin < heard[frame].length; bin++) {
                double model = frame < shared ? gain * best[frame][bin] : 0;
                residual += (heard[frame][bin] - model) * (heard[frame][bin] - model);
            }
            left.add(residual / energy);
        }
        if (left.size() < MIN_NOTES) return new Reading(left.size(), null, "unmeasured", kind);
        double unexplained = median(left);
        return new Reading(left.size(), unexplained, unexplained <= ADEQUATE_UNEXPLAINED ? "adequate" : "inadequate", kind);
    }

    /**
     * Both measurements for every tonal part of a finished reconstruction, by source
     * instrument, with each part's fitted shortlist as its candidates. A part whose stem is
     * the mix, is shared with another part or cannot be read is listed as unavailable with
     * the reason. One part's failure never stops the others.
     */
    static JsonObject report(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        var target = score.inputs().targetGame();
        var catalogue = InstrumentCatalogue.openReconstruction(Path.of(score.inputs().catalogue()), target);
        var decoder = new VoiceNoteDecode(catalogue, target);
        var stems = new java.util.HashMap<Integer, Path>();
        score.inputs().stems().forEach((id, file) -> stems.put(id, Path.of(file)));
        var json = new com.google.gson.Gson();
        var parts = new JsonObject();
        for (var part : VoiceRetrieval.parts(score, allocation).entrySet()) {
            String id = ReconstructionVoicePlan.SourceInstrument.id(part.getKey());
            var sound = voicing.sourceInstruments() == null ? null : voicing.sourceInstruments().get(id);
            String unavailable = null;
            // Files are compared by identity, so another spelling of the mix or of another part's stem does not pass.
            if (!ReconstructionVoiceStage.challengeable(id, null, part.getKey(), stems, Path.of(score.inputs().wav())))
                unavailable = "the stem is missing, is the mix, or is shared with another part";
            else if (sound == null || sound.fm().variantId() == null) unavailable = "the part has no fitted FM patch";
            if (unavailable == null) try {
                var candidates = new ArrayList<String>();
                candidates.add(sound.fm().variantId());
                sound.fm().shortlist().forEach(patch -> candidates.add(patch.variantId()));
                parts.add(id, json.toJsonTree(measure(decoder, catalogue, target, part.getValue(), stems.get(part.getKey()), candidates)));
                continue;
            } catch (IOException | RuntimeException unreadable) {
                unavailable = unreadable.getMessage() == null ? unreadable.toString() : unreadable.getMessage();
            }
            var reason = new JsonObject();
            reason.addProperty("unavailable", unavailable);
            parts.add(id, reason);
        }
        var result = new JsonObject();
        result.addProperty("tentative", "Diagnostic only. The fit label compares a residual against the fitted shortlist, "
                + "and the kind label comes from a cut that was right for about 7 parts in 10 on held-out songs.");
        result.add("parts", parts);
        return result;
    }

    /** Up to {@value #SAMPLE} sustained notes at or above a pitch, spread over the part. */
    private static List<VoiceNoteDecode.Note> sample(List<VoiceNoteDecode.Note> notes, int lowest) {
        var eligible = notes.stream().filter(note -> note.end() - note.start() >= MIN_SECONDS && note.pitch() >= lowest).toList();
        if (eligible.size() <= SAMPLE) return eligible;
        var chosen = new ArrayList<VoiceNoteDecode.Note>();
        for (int i = 0; i < SAMPLE; i++) chosen.add(eligible.get((int) ((long) i * eligible.size() / SAMPLE)));
        return chosen;
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
    }
}
