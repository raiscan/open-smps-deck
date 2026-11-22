package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
    static final String GROUP = "vocal-lead-review";

    private ReconstructionVocalReview() {}

    record Contour(Double medianMidi, Double slopeSemitonesPerSecond, Double vibratoRmsSemitones,
                   double pitchConfidence, double voicingFraction) {}
    record LeadNote(String noteId, double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                    int pitch, Double syllableOnset, List<String> onsetEvidence, Contour contour,
                    VocalStemEvidence.Profile harmonics, VocalPhraseRepeats.Consensus repeat) {}
    record Flag(String type, double sourceTime, JsonObject proposal) {}
    record Span(String id, double start, double end, boolean instrumental) {
        Span(String id, double start, double end) { this(id, start, end, false); }
    }
    record Phrase(String id, double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                  List<LeadNote> notes, List<Flag> flags) {}
    record Boundaries(int[] syllable, int[] voicing, int[] pitchChange) {}
    record KeptNote(String noteId, int pitch, double sourceStart, double sourceEnd,
                    double projectStart, double projectEnd) {}

    record ReviewData(int part, int shift, String stem, double[] pitches, Boundaries boundaries,
                      List<Span> spans, List<Note> notes, ReconstructionTimeMap time,
                      VocalStemEvidence audio, ReconstructionBeatMap grid, List<KeptNote> kept) {}

    static void cli(String[] args, PrintStream out) throws IOException {
        require(args.length > 0, "vocal-review", "expected WORK_DIR [--phrase ID] [--propose] [--show-decided] [--json]");
        String phrase = null;
        boolean propose = false, decided = false, json = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--phrase" -> {
                    require(phrase == null && i + 1 < args.length, "vocal-review", "expected one phrase ID");
                    phrase = args[++i];
                }
                case "--propose" -> propose = true;
                case "--show-decided" -> decided = true;
                case "--json" -> json = true;
                default -> throw new IllegalArgumentException("vocal-review: unknown option " + args[i]);
            }
        }
        var result = review(Path.of(args[0]), phrase, propose, decided);
        out.println(json ? JSON.toJson(result) : new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    public static JsonObject review(Path work, String phraseId, boolean saveProposals) throws IOException {
        return review(work, phraseId, saveProposals, false);
    }

    public static JsonObject review(Path work, String phraseId, boolean saveProposals, boolean showDecided)
            throws IOException {
        var context = load(work);
        var repeat = VocalPhraseRepeats.align(context.spans, context.notes);
        var counts = new TreeMap<String, Integer>();
        for (String type : List.of("octaveJump", "contourDisagreement", "shortBetweenSamePitch", "missingSyllableNote",
                "noOnsetEvidence", "outsidePhraseRange", "noVocal", "repeatDisagreement", "moveSyllableAttack",
                "mergeSyllableFragments")) counts.put(type, 0);
        var candidates = new ArrayList<Phrase>();
        for (var span : context.spans) {
            if (phraseId != null && !span.id.equals(phraseId)) continue;
            candidates.add(analyze(context.part, span, context.notes, context.pitches, context.boundaries, context.time,
                    context.audio, context.grid, context.shift, repeat));
        }
        require(!candidates.isEmpty(), "phrase", "unknown phrase ID " + phraseId);
        var generated = candidates.stream().flatMap(phrase -> phrase.flags.stream()).map(Flag::proposal).toList();
        var state = ReconstructionEditCommand.vocalProposals(work, generated, saveProposals,
                candidates.stream().map(phrase -> new Span(phrase.id, phrase.sourceStart, phrase.sourceEnd)).toList());
        var edits = new ArrayList<JsonObject>();
        var phrases = new ArrayList<Phrase>();
        for (var phrase : candidates) {
            var pending = new ArrayList<Flag>();
            for (var flag : phrase.flags) {
                String id = flag.proposal.get("id").getAsString();
                var entry = state.entries().getOrDefault(id, flag.proposal);
                if (!ReconstructionProposals.isProposed(entry)) continue;
                pending.add(new Flag(flag.type, flag.sourceTime, entry));
                counts.merge(flag.type, 1, Integer::sum);
                edits.add(entry);
            }
            if (!phrase.notes.isEmpty() || !pending.isEmpty()) phrases.add(new Phrase(phrase.id, phrase.sourceStart,
                    phrase.sourceEnd, phrase.projectStart, phrase.projectEnd, phrase.notes, pending));
        }
        var result = JSON.toJsonTree(Map.of("part", context.part, "registerShift", context.shift,
                "flagsByType", counts, "phrases", phrases, "proposalsSaved", state.saved(), "edits", edits)).getAsJsonObject();
        if (context.stem != null) result.addProperty("stem", context.stem);
        result.add("syllables", JSON.toJsonTree(Arrays.stream(context.boundaries.syllable).mapToDouble(i -> i / 100.0)
                .filter(seconds -> candidates.stream().anyMatch(phrase -> seconds >= phrase.sourceStart && seconds < phrase.sourceEnd))
                .toArray()));
        result.add("keptFm1Notes", JSON.toJsonTree(context.kept.stream().filter(note -> candidates.stream()
                .anyMatch(phrase -> note.sourceStart < phrase.sourceEnd && note.sourceEnd > phrase.sourceStart)).toList()));
        if (showDecided) result.add("decided", JSON.toJsonTree(state.decided()));
        return result;
    }

    static ReviewData load(Path work) throws IOException {
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
        var arrangement = ReconstructionStages.arrangement(work.resolve("voicing.json"));
        int shift = arrangement != null && arrangement.vocalRegisterShift() != null
                ? arrangement.vocalRegisterShift()
                : Integer.parseInt(metadata.getOrDefault("vocalRegisterShift", "0"));
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
        var sections = ReconstructionSections.load(work, score).sections();
        var phrases = sections.stream().filter(section -> section.level() == ReconstructionSections.Level.phrase)
                .sorted(Comparator.comparingDouble(ReconstructionSections.Section::sourceStart)).toList();
        var instrumental = sections.stream().filter(section -> section.label().matches("(?i).*(\\binstrumental\\b|\\bno vocals?\\b).*")).toList();
        for (var section : phrases) {
            if (section.sourceStart() > start) spans.add(new Span("unphrased-" + spans.size(), start, section.sourceStart()));
            boolean noVocal = instrumental.stream().anyMatch(parent ->
                    parent.sourceStart() <= section.sourceStart() && parent.sourceEnd() >= section.sourceEnd());
            spans.add(new Span(section.id(), section.sourceStart(), section.sourceEnd(), noVocal));
            start = section.sourceEnd();
        }
        if (start < time.sourceDuration()) spans.add(new Span("unphrased-" + spans.size(), start, time.sourceDuration()));
        String stem = inputs.stems().get(part);
        var audio = stem != null && Files.isRegularFile(Path.of(stem))
                ? new VocalStemEvidence(ReconstructionLeadAudio.readMono(Path.of(stem), time.sourceDuration())) : null;
        var grid = score.beatMap();
        if (grid == null && score.tempo() != null && score.tempo().selected() != null)
            grid = ReconstructionBeatMap.constant(score.tempo().selected(), time.sourceDuration());
        var allocation = work.resolve("allocation.json");
        var kept = Files.isRegularFile(allocation)
                ? ReconstructionArtifacts.read(allocation, ReconstructionAllocation.Result.class).notes().stream()
                    .filter(row -> "FM1".equals(row.lane()) && row.startTick() != null && row.endTick() != null
                            && row.outcome() != ReconstructionAllocation.Outcome.dropped)
                    .map(row -> {
                        var gate = ReconstructionTrace.ticks(row.startTick(), row.endTick(), time);
                        return new KeptNote(row.note().id(), row.note().pitch(), gate.sourceStart(), gate.sourceEnd(),
                                gate.projectStart(), gate.projectEnd());
                    }).sorted(Comparator.comparingDouble(KeptNote::sourceStart)).toList()
                : List.<KeptNote>of();
        return new ReviewData(part, shift, stem, pitches, boundaries, spans, notes, time, audio, grid, kept);
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
        return analyze(part, span, all, f0, boundaries, time, null, null, 0, Map.of());
    }

    static Phrase analyze(int part, Span span, List<Note> all, double[] f0, Boundaries boundaries,
                          ReconstructionTimeMap time, VocalStemEvidence audio, ReconstructionBeatMap grid,
                          int shift, Map<String, VocalPhraseRepeats.Consensus> repeats) {
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
            var profile = audio == null ? null : audio.profile(note.start(), note.end(), note.pitch() - shift);
            var repeat = repeats.get(note.id());
            review.add(new LeadNote(note.id(), note.start(), note.end(), time.project(note.start()),
                    time.project(note.end()), note.pitch(), onset, reasons, contour, profile, repeat));

            var target = Map.<String, Object>of("noteId", note.id());
            String evidence = noteEvidence(note, contour, profile, repeat);
            boolean isolated = (before == null || note.start() - before.end() >= .09)
                    && (after == null || after.start() - note.end() >= .09);
            if (span.instrumental || audio != null && (audio.quiet(note.start(), note.end(), onset != null)
                    || isolated && onset == null && profile.rmsDb() < -30)) {
                flags.add(flag(part, note.start(), "noVocal", span.instrumental ? "Instrumental section" : "Below the vocal activity gate",
                        "deleteNote", target, evidence));
                continue;
            }
            boolean isolatedBlip = before != null && after != null
                    && note.start() - before.end() >= .06 && after.start() - note.end() >= .06
                    && Math.abs(note.pitch() - before.pitch()) >= 9 && Math.abs(note.pitch() - after.pitch()) >= 9
                    && contour.pitchConfidence < .55 && (profile == null || !profile.supportsLowerOctave());
            if (onset == null && note.end() - note.start() < .12
                    && (isolatedBlip || reasons.isEmpty() && contour.voicingFraction < .50
                    && before != null && after != null
                    && note.start() - before.end() <= .25 && after.start() - note.end() <= .25)) {
                flags.add(flag(part, note.start(), "noOnsetEvidence", "Isolated gate with weak voicing and no boundary",
                        "deleteNote", target, evidence));
                continue;
            }

            // At a deliberately changed register, a lower partial by itself is
            // insufficient evidence of an octave error in a contour-supported note.
            boolean registered = shift != ExternalTranscribers.VocalOptions.DEFAULT.registerShift()
                    && shift != 0 && contour.pitchConfidence >= .65;
            var octave = octaveFlag(part, note, before, after, notes, contour, profile, repeat, evidence, registered);
            if (octave == null && !registered && audio != null && onset == null && after != null
                    && after.pitch() == note.pitch() - 12 && after.start() - note.end() < .025
                    && note.end() - note.start() <= .16 && contour.vibratoRmsSemitones != null
                    && contour.vibratoRmsSemitones > 2) {
                var joined = audio.profile(note.start(), after.end(), note.pitch() - shift);
                if (joined.supportsLowerOctave() || joined.subharmonicDb() != null && joined.subharmonicDb() >= 15)
                    octave = flag(part, note.start(), "octaveJump", "An unarticulated octave flip shares the following vowel",
                            "setNote", Map.of("noteId", note.id(), "pitch", after.pitch()), evidence + ":joined-vowel");
            }
            if (octave != null) {
                flags.add(octave);
                continue;
            }
            boolean confirmed = profile != null && profile.supportsSungOctave();
            boolean repeatAgrees = repeat != null && repeat.pitch() == note.pitch();
            if (before != null && after != null && note.start() - before.end() <= .25 && after.start() - note.end() <= .25
                    && note.end() - note.start() < .12 && before.pitch() == after.pitch() && onset == null && !repeatAgrees) {
                if (before.pitch() == note.pitch() && after.start() - before.end() - (note.end() - note.start()) <= .06)
                    flags.add(flag(part, note.start(), "shortBetweenSamePitch", "Join three adjacent same-pitch gates",
                            "mergeNotes", Map.of("noteIds", List.of(before.id(), note.id(), after.id())), evidence));
                else if (Math.abs(before.pitch() - note.pitch()) < 10 || !confirmed)
                    flags.add(flag(part, note.start(), "shortBetweenSamePitch", "Review a short interruption between matching neighbours",
                            "deleteNote", target, evidence));
            }

            if (repeat != null && repeat.pitch() != note.pitch() && contour.medianMidi != null
                    && (Math.abs(repeat.pitch() - note.pitch()) < 3
                        ? contour.pitchConfidence < .85 && Math.abs(contour.medianMidi - note.pitch()) >= .4 : !confirmed)
                    && (Math.abs(contour.medianMidi - repeat.pitch()) <= .65
                    || Math.abs(repeat.pitch() - note.pitch()) == 12 && contour.pitchConfidence < .50)) {
                flags.add(flag(part, note.start(), "repeatDisagreement", "Match aligned phrase repeats where local pitch is uncertain",
                        "setNote", Map.of("noteId", note.id(), "pitch", repeat.pitch()), evidence));
                continue;
            }
            if (contour.medianMidi != null && Math.abs(contour.medianMidi - note.pitch()) > PITCH_TOLERANCE) {
                int pitch = playable(contour.medianMidi);
                if (pitch != note.pitch() && !(Math.abs(pitch - note.pitch()) >= 10 && (confirmed || repeatAgrees))) {
                    var curve = bend(f0, note);
                    flags.add(curve.isEmpty()
                            ? flag(part, note.start(), "contourDisagreement", "Use the rounded RMVPE median for this gate",
                                    "setNote", Map.of("noteId", note.id(), "pitch", pitch), evidence)
                            : flag(part, note.start(), "contourDisagreement", "Follow a supported pitch excursion inside this gate",
                                    "bend", Map.of("noteId", note.id(), "points", curve), evidence));
                }
            }
        }
        if (!span.instrumental) syllableFlags(part, span, all, f0, boundaries, time, audio, grid, flags);
        flags.sort(Comparator.comparingDouble(Flag::sourceTime).thenComparing(Flag::type));
        return new Phrase(span.id, span.start, span.end, time.project(span.start), time.project(span.end), review, flags);
    }

    private static Flag octaveFlag(int part, Note note, Note before, Note after, List<Note> phrase, Contour contour,
                                   VocalStemEvidence.Profile profile, VocalPhraseRepeats.Consensus repeat, String evidence,
                                   boolean registered) {
        boolean lower = profile != null && profile.supportsLowerOctave();
        if (!lower && (profile != null && profile.supportsSungOctave()
                || repeat != null && repeat.pitch() == note.pitch())) return null;
        int low = note.pitch() - 12;
        boolean neighbouringRegister = before != null && after != null
                && note.start() - before.end() <= .15 && after.start() - note.end() <= .15
                && Math.abs(low - before.pitch()) <= 2 && Math.abs(low - after.pitch()) <= 2;
        if (lower && low >= 12 && (neighbouringRegister
                || repeat != null && repeat.pitch() == low
                || !registered && profile.subharmonicDb() > profile.oddHarmonicDb() + 4)
                && (repeat == null || repeat.pitch() != note.pitch() || profile.subharmonicDb() > profile.oddHarmonicDb() + 4))
            return flag(part, note.start(), "octaveJump", "Half and three-half partials support the lower octave",
                    "setNote", Map.of("noteId", note.id(), "pitch", note.pitch() - 12), evidence);
        var range = phrase.stream().filter(other -> other != note).mapToInt(Note::pitch).sorted().toArray();
        for (int delta : new int[]{-12, 12}) {
            int fold = note.pitch() + delta;
            if (fold < 12 || fold > 106) continue;
            boolean supported = contour.medianMidi != null && Math.abs(contour.medianMidi - fold) <= 1
                    || repeat != null && repeat.pitch() == fold && contour.pitchConfidence < .50;
            if (!supported) continue;
            if (before != null && after != null && note.start() - before.end() <= .25 && after.start() - note.end() <= .25
                    && Math.abs(fold - before.pitch()) <= 2 && Math.abs(fold - after.pitch()) <= 2)
                return flag(part, note.start(), "octaveJump", "Fold toward both neighbours with local pitch support",
                        "setNote", Map.of("noteId", note.id(), "pitch", fold), evidence);
            int margin = range.length / 10;
            if (range.length >= 4 && fold >= range[margin] && fold <= range[range.length - 1 - margin]
                    && (note.pitch() < range[margin] - 2 || note.pitch() > range[range.length - 1 - margin] + 2))
                return flag(part, note.start(), "outsidePhraseRange", "Fold by one octave into the surrounding phrase range",
                        "setNote", Map.of("noteId", note.id(), "pitch", fold), evidence);
        }
        return null;
    }

    private static String noteEvidence(Note note, Contour contour, VocalStemEvidence.Profile profile,
                                       VocalPhraseRepeats.Consensus repeat) {
        return note.pitch() + ":" + (contour.medianMidi == null ? "unvoiced" : Math.round(contour.medianMidi))
                + ":" + (contour.voicingFraction >= .5)
                + ":" + (profile == null ? "unknown" : profile.supportsLowerOctave() ? "lower"
                    : profile.supportsSungOctave() ? "sung" : "uncertain")
                + ":" + (repeat == null ? "unknown" : repeat.pitch());
    }

    private static void syllableFlags(int part, Span span, List<Note> all, double[] f0, Boundaries boundaries,
                                      ReconstructionTimeMap time, VocalStemEvidence audio, ReconstructionBeatMap grid,
                                      List<Flag> flags) {
        var moved = new java.util.HashSet<String>();
        for (int i = 0; i < boundaries.syllable.length; i++) {
            int frame = boundaries.syllable[i];
            double onset = frame / 100.0;
            if (onset < span.start || onset >= span.end) continue;
            if (all.stream().anyMatch(note -> Math.abs(note.start() - onset) <= ONSET_TOLERANCE)) continue;
            var sounding = all.stream().filter(note -> note.start() < onset && note.end() > onset).findFirst().orElse(null);
            boolean release = vowelRelease(f0, frame);
            var following = all.stream().filter(note -> note.start() > onset).findFirst().orElse(null);
            int vowel = firstVowel(f0, frame, 20);
            if (vowel < 0 || preBeatConsonant(onset, vowel / 100.0, grid)) continue;
            int followingVowel = following == null ? -1 : firstVowel(f0,
                    Math.max(frame, frame(following.start() - .02, f0.length)), 8, following.pitch());
            boolean separateVowel = following != null && IntStream.range(vowel, frame(following.start(), f0.length))
                    .filter(sample -> f0[sample] > 0 && Math.abs(f0[sample] - following.pitch()) >= 7).count() >= 6;
            if (followingVowel >= 0 && !separateVowel && (sounding == null || sounding.end() <= onset + .015)
                    && (following.start() <= onset + .121
                        || following.start() <= onset + .171 && !release && vowel <= frame + 3
                        && Math.abs(f0[vowel] - following.pitch()) <= 1.4)
                    && Math.abs(f0[followingVowel] - following.pitch()) <= 2
                    && moved.add(following.id())) {
                flags.add(flag(part, onset, "moveSyllableAttack", "Start the following vowel gate at its syllable",
                        "setNote", Map.of("noteId", following.id(), "start", onset), "vowel-" + (followingVowel - frame) / 4));
                continue;
            }
            if (release || (sounding != null && vowel > frame + 5)) continue;
            if (audio != null && audio.quiet(vowel / 100.0, (vowel + 6) / 100.0, false)) continue;
            if (sounding != null && canSplit(sounding, onset, time)) {
                var contour = summarize(f0, onset, sounding.end(), sounding.pitch());
                if (contour.voicingFraction >= .6 && contour.medianMidi != null)
                    flags.add(flag(part, onset, "missingSyllableNote", "Retrigger the held gate at the supported syllable onset",
                            "splitNote", Map.of("noteId", sounding.id(), "at", onset, "pitch", playable(contour.medianMidi)),
                            "voiced-split"));
                continue;
            }
            if (vowel > frame + 10) continue;
            double next = following == null ? time.sourceDuration() : following.start();
            if (i + 1 < boundaries.syllable.length) next = Math.min(next, boundaries.syllable[i + 1] / 100.0);
            double end = Math.min(next, voicedEnd(f0, vowel) / 100.0);
            if (end - onset < .059) continue;
            var contour = summarize(f0, onset, end, 0);
            if (i + 1 < boundaries.syllable.length) {
                int adjacent = boundaries.syllable[i + 1];
                double mergedEnd = Math.min(following == null ? time.sourceDuration() : following.start(),
                        voicedEnd(f0, Math.min(adjacent, vowel)) / 100.0);
                if (adjacent - frame <= 20 && adjacent / 100.0 < span.end && mergedEnd >= adjacent / 100.0 + .059
                        && mergedEnd - onset <= .31 && adjacent <= f0.length && f0[adjacent - 1] > 0) {
                    var combined = summarize(f0, adjacent / 100.0, mergedEnd, 0);
                    var leadIn = summarize(f0, Math.max(onset, (adjacent - 5) / 100.0), adjacent / 100.0, 0);
                    if (combined.medianMidi != null && leadIn.medianMidi != null && combined.voicingFraction >= .6
                            && combined.vibratoRmsSemitones <= 1.5
                            && Math.abs(combined.medianMidi - leadIn.medianMidi) <= 1.2) {
                        flags.add(insert(part, onset, mergedEnd, playable(combined.medianMidi), true));
                        i++;
                        continue;
                    }
                }
            }
            if (contour.medianMidi == null || contour.voicingFraction < .6) continue;
            long stable = IntStream.range(frame(onset, f0.length), frame(end, f0.length))
                    .filter(sample -> Math.abs(f0[sample] - contour.medianMidi) <= 1.8).count();
            if (contour.vibratoRmsSemitones <= 1.5 && (stable >= 6
                    || end - onset >= .089 && contour.voicingFraction >= .9 && contour.vibratoRmsSemitones <= .85)) {
                flags.add(insert(part, onset, end, playable(contour.medianMidi), false));
            } else if (separateVowel) {
                var lower = summarize(f0, onset, end, following.pitch() - 12);
                long octaveFrames = IntStream.range(frame(onset, f0.length), frame(end, f0.length))
                        .filter(sample -> Math.abs(f0[sample] - (following.pitch() - 12)) <= 1.8).count();
                if (octaveFrames >= 6 && lower.voicingFraction >= .65 && following.pitch() - 12 >= 12)
                    flags.add(insert(part, onset, end, following.pitch() - 12, false));
            }
        }
    }

    static boolean vowelRelease(double[] f0, int onset) {
        if (onset < 6 || onset + 20 >= f0.length) return false;
        int before = 0;
        for (int i = onset - 6; i < onset; i++) if (f0[i] > 0) before++;
        if (before < 4) return false;
        int stop = onset;
        while (stop < onset + 10 && f0[stop] > 0) stop++;
        int next = stop;
        while (next < onset + 20 && f0[next] == 0) next++;
        return stop < onset + 10 && next - stop >= 8;
    }

    static int firstVowel(double[] f0, int onset, int lookahead) {
        return firstVowel(f0, onset, lookahead, null);
    }

    private static int firstVowel(double[] f0, int onset, int lookahead, Integer pitch) {
        for (int i = onset; i < onset + lookahead && i + 6 <= f0.length; i++) {
            if (f0[i] <= 0 || pitch != null && Math.abs(f0[i] - pitch) > 2) continue;
            int stable = 1;
            for (int j = i + 1; j < i + 6; j++) if (f0[j] > 0 && Math.abs(f0[j] - f0[i]) <= 2) stable++;
            if (stable >= 5) return i;
        }
        return -1;
    }

    private static boolean preBeatConsonant(double onset, double vowel, ReconstructionBeatMap grid) {
        if (grid == null || grid.beats().size() < 2 || vowel - onset <= .12) return false;
        double beat = grid.seconds(Math.rint(grid.index(vowel)));
        return Math.abs(vowel - beat) < .055 && Math.abs(onset - beat) > .10;
    }

    private static Flag insert(int part, double onset, double end, int pitch, boolean merged) {
        return flag(part, onset, merged ? "mergeSyllableFragments" : "missingSyllableNote",
                merged ? "Use one gate for two adjacent fragments of a continuous vowel"
                        : "Restore the missing attack using sustained voiced frames",
                "insertNote", Map.of("part", part, "start", onset, "end", end, "pitch", pitch,
                        "velocity", 96, "role", "melody", "lane", "FM1"), "voiced-insert");
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

    private static Flag flag(int part, double onset, String type, String reason, String op,
                             Map<String, Object> fields, String materialEvidence) {
        var entry = JSON.toJsonTree(new TreeMap<>(fields)).getAsJsonObject();
        entry.addProperty("op", op);
        String signature = ReconstructionArtifacts.fingerprint(List.of(part, type, entry, materialEvidence)).substring(0, 20);
        entry.addProperty("id", "vocal-" + part + "-" + Math.round(onset * 1_000_000) + "-" + type + "-" + signature);
        entry.addProperty("reason", reason);
        entry.addProperty("group", GROUP);
        entry.addProperty("timeBase", "source");
        entry.add("evidence", JSON.toJsonTree(Map.of("path", "score.json", "text", reason)));
        return new Flag(type, onset, ReconstructionProposals.proposed(entry));
    }
}
