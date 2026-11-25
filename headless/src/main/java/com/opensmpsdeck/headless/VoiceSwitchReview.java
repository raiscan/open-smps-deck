package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/**
 * Finds passages where one source part is better explained by a second
 * stock FM patch. The per-note decode supplies the evidence. Each passage
 * must then pass every guard to be applied on its own authority. The rest
 * become proposals in the edit journal, or are only reported.
 */
public final class VoiceSwitchReview {
    public static final String GROUP = "voice-switch";
    /** The last review with every guard result, saved beside the journal when it proposes or applies. */
    public static final String FILE = "voice-switches.json";
    /** Distinct key-ons a passage needs before it can be a change of instrument. */
    static final int MIN_ONSETS = 8;
    static final double DEFAULT_MIN_SECONDS = 4;
    // Cosine gained per note, the smallest difference worth a patch change.
    private static final double MARGIN = .01;
    // Below this share of notes won by one patch, the stock bank does not explain the stem
    // and the decode only says what is admissible. Native parts read 0.5 to 1, real stems 0.1 to 0.4.
    static final double EXPLAINED_SHARE = .5;
    /** Alternate patches one part may take, counting accepted switches already in the journal. */
    static final int MAX_ALTERNATES = 2;
    // A passage is shorter than a part, so its attack comparison uses shorter blocks.
    private static final double PASSAGE_BLOCK_SECONDS = 2;
    private static final int NULL_SHUFFLES = 200;
    // Notes in one section and one register band are the ones a single patch should explain alike.
    private static final int REGISTER_BAND = 12;
    private static final double NULL_BLOCK_SECONDS = 1;
    /** Notes held past the attack that a passage needs before it can apply unseen. */
    static final int MIN_HELD = 3;
    // What the review reads besides the journals. A rebuild staged from other bytes is not the one reviewed.
    private static final List<String> READ = List.of("score.json", "allocation.json", "voicing.json",
            ReconstructionVoicePlan.FILE, "structure.json");
    private static final double NULL_LEVEL = .02;
    private static final com.google.gson.Gson JSON =
            new com.google.gson.GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    public enum Mode { off, propose, auto }

    record Guard(String name, boolean pass, String detail) {}

    /** A run of consecutive notes the decode gives to one patch other than the part's own. */
    record Passage(int part, String variantId, String voice, double start, double end, int onsets, double voicedSeconds,
                   double meanGain, List<Guard> guards, String confidence, int bankBytes, int switchBytes,
                   Double gain, List<String> noteIds) {
        boolean high() { return confidence.equals("high"); }

        /** The same passage with one more guard result. A failed guard keeps it out of automatic application. */
        Passage with(Guard guard, String fallback) {
            var all = new ArrayList<>(guards);
            all.add(guard);
            return new Passage(part, variantId, voice, start, end, onsets, voicedSeconds, meanGain, List.copyOf(all),
                    guard.pass() ? confidence : high() ? fallback : confidence.equals("review") && fallback.equals("rejected")
                            ? "rejected" : confidence, bankBytes, switchBytes, gain, noteIds);
        }
    }

    /** The decoded patch and the note's own best patch, for measurement. */
    record NoteLabel(String id, double start, double end, int pitch, String decoded, String nearest) {}

    record PartReport(int part, String name, String stem, boolean isolated, int notes, int scored, String home,
                      String homeVoice, int homeRank, String majority, String majorityVoice, double modeShare,
                      int switches, List<Passage> passages, List<NoteLabel> labels, List<String> candidates,
                      Boolean majorityWinsAttacks) {
        PartReport withPassages(List<Passage> revised) {
            return new PartReport(part, name, stem, isolated, notes, scored, home, homeVoice, homeRank, majority,
                    majorityVoice, modeShare, switches, revised, labels, candidates, majorityWinsAttacks);
        }

        PartReport withAttacks(Boolean wins) {
            return new PartReport(part, name, stem, isolated, notes, scored, home, homeVoice, homeRank, majority,
                    majorityVoice, modeShare, switches, passages, labels, candidates, wins);
        }
    }

    record Options(Integer part, double minSeconds, boolean figures, Mode mode, boolean labels) {
        Options(Integer part, double minSeconds, boolean figures, Mode mode) { this(part, minSeconds, figures, mode, false); }
    }

    private VoiceSwitchReview() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException(
                "reconstruct voice-switches expects WORK_DIR [--part ID] [--min-run SECONDS] [--figures] [--labels] [--propose | --apply]");
        Integer part = null;
        boolean labels = false;
        double minSeconds = DEFAULT_MIN_SECONDS;
        boolean figures = false;
        Mode mode = Mode.off;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--part" -> part = Integer.parseInt(args[++i]);
                case "--min-run" -> minSeconds = Double.parseDouble(args[++i]);
                case "--figures" -> figures = true;
                case "--labels" -> labels = true;
                case "--propose" -> mode = Mode.propose;
                case "--apply" -> mode = Mode.auto;
                default -> throw new IllegalArgumentException("Unknown voice-switches option " + args[i]);
            }
        }
        require(minSeconds > 0, GROUP, "expected a positive --min-run");
        out.println(JSON.toJson(review(Path.of(args[0]), new Options(part, minSeconds, figures, mode, labels))));
    }

    /**
     * Reports every stem-backed FM part. In {@code propose} mode the passages worth a
     * look enter the edit journal as proposals. In {@code auto} mode those that pass
     * every guard are added as accepted agent edits and the others are proposed.
     * A passage already in the journal, in any state, is left as it was decided.
     * Nothing is written if the work directory changes while the review runs.
     */
    public static JsonObject review(Path work, Options options) throws IOException {
        // The journals and the artifacts are captured once. Everything below is decided from this
        // capture, and nothing is written unless the directory still matches it.
        var revision = ReconstructionReview.revision(work);
        var read = snapshot(work);
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        require(voicing.sourceInstruments() != null, GROUP, "voicing has no source instruments to review");
        var catalogue = InstrumentCatalogue.openReconstruction(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
        var time = ReconstructionTimeMap.of(score);
        var journal = ReconstructionReview.parse(revision.entries());
        var plan = ReconstructionVoicePlan.saved(work);
        var bands = sectionStarts(ReconstructionSections.load(work, score));
        if (!sameSnapshot(read, snapshot(work)) || !revision.equals(ReconstructionReview.revision(work)))
            throw new ReconstructionReview.Changed();
        var known = new HashSet<String>();
        for (var edit : journal.intents()) known.add(edit.id());
        var locked = userChoices(voicing, plan, journal, allocation);
        var stems = new java.util.HashMap<Integer, Path>();
        score.inputs().stems().forEach((id, file) -> stems.put(id, Path.of(file)));
        var decoder = new VoiceNoteDecode(catalogue, score.inputs().targetGame());
        // The stage's level-matched attack comparison is an independent check of the decode's preferred patch.
        var placement = ReconstructionAllocation.sourcePlacement(allocation, score.frames(), time);
        var windows = new VoiceWindows(score.frames(), ReconstructionVoicing.byLane(placement), voicing.fitWindows());
        var screening = new VoicePatchShortlist(catalogue, score.inputs().targetGame());
        var homes = new HashSet<String>();
        voicing.sourceInstruments().values().forEach(sound -> {
            if (sound.fm().variantId() != null) homes.add(catalogue.match(sound.fm().variantId()).variant().assetId());
        });
        var reports = new ArrayList<PartReport>();
        var proposed = new JsonArray();
        var offers = new ArrayList<java.util.Map.Entry<Double, JsonObject>>();
        for (var part : score.source().parts()) {
            if (options.part() != null && options.part() != part.id()) continue;
            var sound = voicing.sourceInstruments().get(ReconstructionVoicePlan.SourceInstrument.id(part.id()));
            String stem = score.inputs().stems().get(part.id());
            if (sound == null || sound.fm().variantId() == null || stem == null || stem.equals(score.inputs().wav())) continue;
            // Two parts on one file, or the mix itself, cannot say which part changed. Files are
            // compared by identity, so another spelling of the same stem does not pass.
            String id = ReconstructionVoicePlan.SourceInstrument.id(part.id());
            boolean isolated = !score.inputs().wholePartVoices() && ReconstructionVoiceStage.challengeable(id,
                    ReconstructionVocalLead.isVocal(score.inputs(), part.id()) ? id : null, part.id(), stems,
                    Path.of(score.inputs().wav()));
            var lanes = new java.util.HashMap<String, String>();
            var notes = new ArrayList<VoiceNoteDecode.Note>();
            for (var entry : allocation.notes()) {
                var note = entry.note();
                if (note.part() != part.id() || entry.role() == ReconstructionRoles.Role.drums) continue;
                if (allocation.timeBase() == ReconstructionTimeMap.TimeBase.project) note = time.sourceNote(note);
                // Every note sounds on the stem and is evidence. Only notes on an FM lane can take a patch.
                notes.add(new VoiceNoteDecode.Note(note.id(), note.start(), note.end(), note.pitch()));
                if (entry.lane() != null && entry.lane().startsWith("FM") && entry.mergedInto() == null)
                    lanes.put(note.id(), entry.lane());
            }
            if (notes.isEmpty()) continue;
            notes.sort(Comparator.comparingDouble(VoiceNoteDecode.Note::start).thenComparing(VoiceNoteDecode.Note::id));
            var decoded = decoder.decode(notes, Path.of(stem), List.of(sound.fm().variantId()), options.figures());
            var report = report(part.id(), part.name(), stem, isolated, notes.size(), decoded, sound, catalogue, lanes,
                    homes, options.minSeconds(), options.labels(), locked, bands);
            var entries = placement.notes().stream().filter(entry -> entry.note().part() == part.id()
                    && entry.lane() != null && entry.lane().startsWith("FM") && entry.mergedInto() == null).toList();
            report = confirmed(report, entries, Path.of(stem), windows, screening, journal, catalogue);
            if (report.majority() != null && !report.majority().equals(report.home())) {
                report = report.withAttacks(!entries.isEmpty() && screening.attackWinner(
                        List.of(new VoicePatchShortlist.RankedPatch(report.majority(), 0)),
                        new VoicePatchShortlist.RankedPatch(report.home(), 0), entries, Path.of(stem), windows) != null);
            }
            reports.add(report);
            for (var passage : report.passages()) {
                if (passage.confidence().equals("rejected")) continue;
                // Keep the passage as loud as the part's own patch would have played it. Levels are
                // compared well below full volume, where several carriers would clip the channel.
                int pitch = decoded.notes().stream().filter(note -> passage.noteIds().contains(note.note().id()))
                        .mapToInt(note -> note.note().pitch()).sorted().skip(passage.noteIds().size() / 2).findFirst().orElse(60);
                double gain = sound.fm().gain() * Math.clamp(decoder.loudness(sound.fm().variantId(), pitch)
                        / Math.max(1e-9, decoder.loudness(passage.variantId(), pitch)), .5, 2);
                var edit = edit(passage, Math.round(Math.min(4, gain) * 1000) / 1000.0);
                if (known.contains(edit.get("id").getAsString())) continue;
                if (options.mode() == Mode.auto && passage.high())
                    offers.add(java.util.Map.entry(passage.meanGain() * passage.onsets(), edit));
                else if (options.mode() != Mode.off) proposed.add(edit);
            }
        }
        var result = new JsonObject();
        result.add("parts", JSON.toJsonTree(reports));
        String report = JSON.toJson(result);
        boolean published = false;
        if (!offers.isEmpty()) {
            // Strongest first, across parts, so a build that cannot carry them all drops the weakest.
            offers.sort(java.util.Map.Entry.comparingByKey(Comparator.reverseOrder()));
            var applied = new JsonArray();
            for (var offer : offers) applied.add(offer.getValue());
            var outcome = apply(work, revision, applied, score.profile().bankLimitBytes(), read);
            result.add("applied", outcome);
            if (outcome.has("stale")) return result;
            published = !outcome.getAsJsonArray("edits").isEmpty();
            // An edit the build could not carry stays on offer with the reason.
            if (outcome.has("dropped")) for (var dropped : outcome.getAsJsonArray("dropped")) {
                var offer = dropped.getAsJsonObject().deepCopy();
                String reason = offer.remove("dropped").getAsString();
                offer.addProperty("reason", offer.get("reason").getAsString() + ". Not applied: " + reason);
                proposed.add(offer);
            }
        }
        if (!proposed.isEmpty()) {
            // Adding a proposal appends under the journal lock and refuses a taken ID, so it cannot
            // overwrite anyone. A journal that moved without this review is still not proposed to.
            if (!published && !revision.equals(ReconstructionReview.revision(work))) {
                result.addProperty("stale", STALE);
                return result;
            }
            try {
                result.add("proposals", ReconstructionEditCommand.run(work, "propose", proposed, null, false, (done, total, message) -> {}));
                published = true;
            } catch (IllegalArgumentException taken) {
                result.addProperty("stale", STALE);
            }
        }
        if (published) java.nio.file.Files.writeString(work.resolve(FILE), report);
        return result;
    }

    private static final String STALE = "the work directory changed during the review, so nothing was written. Run it again.";

    private static java.util.Map<String, byte[]> snapshot(Path directory) throws IOException {
        var bytes = new java.util.TreeMap<String, byte[]>();
        for (String name : READ) {
            Path file = directory.resolve(name);
            if (java.nio.file.Files.isRegularFile(file)) bytes.put(name, java.nio.file.Files.readAllBytes(file));
        }
        return bytes;
    }

    private static boolean sameSnapshot(java.util.Map<String, byte[]> first, java.util.Map<String, byte[]> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (var entry : first.entrySet())
            if (!java.util.Arrays.equals(entry.getValue(), second.get(entry.getKey()))) return false;
        return true;
    }

    /** Start times of the top-level sections, in source seconds. */
    private static double[] sectionStarts(ReconstructionSections.Result sections) {
        return sections.sections().stream().filter(section -> section.level() == ReconstructionSections.Level.section)
                .mapToDouble(ReconstructionSections.Section::sourceStart).sorted().toArray();
    }

    private static final class Refused extends IOException {
        Refused(String reason) { super(reason); }
    }

    static JsonObject apply(Path work, JsonArray edits, int bankLimit) throws IOException {
        return apply(work, ReconstructionReview.revision(work), edits, bankLimit, java.util.Map.of());
    }

    /**
     * Builds the journal with the edits in a private copy and publishes it only if the
     * rebuild keeps every note where it was, every reduction, effect and other edit's
     * outcome as they were, and the bank fits. A build that falls short is discarded, the
     * least supported edit is dropped, and the rest are tried again. If the journals or the
     * reviewed artifacts differ from what the review captured, nothing is written at all:
     * the result says it is stale. There is no rollback, because the work directory is not
     * touched until a verified build replaces it under the writer locks.
     *
     * @param edits    in order of support, strongest first
     * @param expected the journals the review read
     * @param read     the artifact bytes the review read, or empty to skip that comparison
     */
    static JsonObject apply(Path work, ReconstructionReview.Revision expected, JsonArray edits, int bankLimit,
                            java.util.Map<String, byte[]> read) throws IOException {
        var result = new JsonObject();
        var dropped = new JsonArray();
        var kept = edits.deepCopy();
        var taken = new HashSet<String>();
        for (var entry : expected.entries()) taken.add(entry.getAsJsonObject().get("id").getAsString());
        for (int i = kept.size() - 1; i >= 0; i--) {
            if (!taken.contains(kept.get(i).getAsJsonObject().get("id").getAsString())) continue;
            var duplicate = kept.remove(i).getAsJsonObject();
            duplicate.addProperty("dropped", "the journal already has an edit with this ID");
            dropped.add(duplicate);
        }
        while (!kept.isEmpty()) {
            var entries = expected.entries();
            entries.addAll(kept.deepCopy());
            var trial = kept;
            var checked = new boolean[1];
            String failure;
            try {
                ReconstructionReview.applyChecked(work, expected, entries, (before, after) -> {
                    if (!read.isEmpty() && !sameSnapshot(read, snapshot(before))) throw new ReconstructionReview.Changed();
                    var was = facts(before);
                    var now = facts(after);
                    String problem = verify(was, now, trial, bankLimit);
                    if (problem == null) problem = moved(before, after);
                    if (problem != null) throw new Refused(problem);
                    result.add("before", sizes(was));
                    result.add("after", sizes(now));
                    checked[0] = true;
                });
                break;
            } catch (ReconstructionReview.Changed changed) {
                result.remove("before");
                result.remove("after");
                result.addProperty("stale", STALE);
                result.add("edits", new JsonArray());
                return result;
            } catch (IOException | RuntimeException error) {
                // Publication restores what it replaced, or names its backups. Either way it is not retried.
                if (checked[0]) throw error;
                failure = error.getMessage() == null ? error.toString() : error.getMessage();
            }
            var last = kept.remove(kept.size() - 1).getAsJsonObject();
            last.addProperty("dropped", failure);
            dropped.add(last);
        }
        result.add("edits", kept);
        if (!dropped.isEmpty()) result.add("dropped", dropped);
        return result;
    }

    /**
     * The first note the rebuild emits differently, or null. A patch change may alter why a
     * note sits where it does, but not its fate, lane, placement, pitch or sounding gates.
     * That holds for the edited notes too, on borrowed lanes, PSG spill and repeats alike.
     */
    private static String moved(Path before, Path after) throws IOException {
        var was = ReconstructionTrace.read(before);
        var now = ReconstructionTrace.read(after);
        if (!was.keySet().equals(now.keySet())) return "the rebuild changed which notes exist";
        for (var row : was.entrySet())
            if (!emitted(row.getValue()).equals(emitted(now.get(row.getKey()))))
                return "the rebuild emits note " + row.getKey() + " differently";
        return null;
    }

    private static List<Object> emitted(ReconstructionTrace.Row row) {
        var placed = row.allocation();
        var gates = new ArrayList<Object>();
        for (var voiced : row.voiced())
            gates.add(java.util.Arrays.asList(voiced.id(), voiced.lane(), voiced.pitch(), voiced.gate(), voiced.bend()));
        return java.util.Arrays.asList(row.status(), placed == null ? null : placed.lane(),
                placed == null ? null : placed.placement(), placed == null ? null : placed.mergedInto(),
                placed == null ? null : placed.effective(), gates);
    }

    /** What a rebuild must not change, read from the assembly report. */
    private static JsonObject facts(Path work) throws IOException {
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        var facts = new JsonObject();
        for (String key : List.of("bankBytes", "fmPatchBankBytes", "fmPatchSwitchBytes", "fmNotes", "psgNotes", "dacHits",
                "reductions", "voicedGates", "echoBankLimit", "echoNotes", "effectCopyLanes", "lanePolicyCopies", "editResults"))
            if (report.has(key)) facts.add(key, report.get(key));
        return facts;
    }

    private static JsonObject sizes(JsonObject facts) {
        var sizes = new JsonObject();
        for (String key : List.of("bankBytes", "fmPatchBankBytes", "fmPatchSwitchBytes"))
            if (facts.has(key)) sizes.add(key, facts.get(key));
        return sizes;
    }

    /** The first way the rebuild fell short, or null. A bank that fits by losing music does not pass. */
    static String verify(JsonObject before, JsonObject after, JsonArray added, int bankLimit) {
        if (!after.has("bankBytes") || after.get("bankBytes").getAsInt() > bankLimit)
            return "compiled bank exceeds " + bankLimit + " bytes";
        for (String key : List.of("fmNotes", "psgNotes", "dacHits", "echoBankLimit", "echoNotes", "effectCopyLanes",
                "lanePolicyCopies"))
            if (!java.util.Objects.equals(before.get(key), after.get(key))) return "the rebuild changed " + key;
        for (String key : List.of("reductions", "voicedGates"))
            if (!elements(before, key).equals(elements(after, key))) return "the rebuild changed " + key;
        var mine = new HashSet<String>();
        for (var edit : added) mine.add(edit.getAsJsonObject().get("id").getAsString());
        var earlier = outcomes(before);
        for (var outcome : outcomes(after).entrySet()) {
            if (mine.contains(outcome.getKey())) {
                if (!outcome.getValue().equals("applied")) return outcome.getKey() + " was " + outcome.getValue();
            } else if (!outcome.getValue().equals(earlier.get(outcome.getKey())))
                return "the rebuild changed the outcome of edit " + outcome.getKey();
        }
        if (!outcomes(after).keySet().containsAll(mine)) return "an added edit has no outcome";
        if (!outcomes(after).keySet().containsAll(earlier.keySet())) return "the rebuild lost an edit outcome";
        return null;
    }

    private static java.util.Set<com.google.gson.JsonElement> elements(JsonObject facts, String key) {
        var set = new HashSet<com.google.gson.JsonElement>();
        if (facts.has(key)) facts.getAsJsonArray(key).forEach(set::add);
        return set;
    }

    /** Edit ID to its statuses, joined, since one edit can report hits and misses separately. */
    private static java.util.Map<String, String> outcomes(JsonObject facts) {
        var result = new java.util.TreeMap<String, String>();
        if (facts.has("editResults")) for (var row : facts.getAsJsonArray("editResults")) {
            var outcome = row.getAsJsonObject();
            result.merge(outcome.get("id").getAsString(), outcome.get("status").getAsString(), (a, b) -> a + "+" + b);
        }
        return result;
    }

    /**
     * Notes whose voice someone chose: an assigned source voice, a note instrument edit in
     * any state, or a lane override that sets a patch or gain. A switch never touches them.
     */
    static java.util.Set<String> userChoices(ReconstructionVoicing.Result voicing, ReconstructionVoicePlan.Result plan,
                                             ReconstructionEdits journal, ReconstructionAllocation.Result allocation) {
        var locked = new HashSet<String>();
        if (voicing.voiceAssignments() != null) locked.addAll(voicing.voiceAssignments().keySet());
        if (plan != null) for (var voice : plan.voices()) if (voice.assignment() != null) locked.addAll(voice.noteIds());
        for (var edit : journal.intents())
            if (edit.kind() == ReconstructionEdits.Kind.noteVoicing && edit.json().has("instrument")) locked.addAll(edit.noteIds());
        var scoped = new ArrayList<java.util.Map.Entry<String, ReconstructionVoicing.Lane>>(voicing.lanes().entrySet());
        for (var lane : scoped) {
            if (lane.getValue().overrides() == null) continue;
            for (var scope : lane.getValue().overrides()) {
                if (scope.variantId() == null && scope.gain() == null) continue;
                // A time range is resolved at assembly. Here every note on the lane is treated as covered.
                if (scope.noteIds() != null) locked.addAll(scope.noteIds());
                else allocation.notes().stream().filter(entry -> lane.getKey().equals(entry.lane()))
                        .forEach(entry -> locked.add(entry.note().id()));
            }
        }
        return locked;
    }

    /**
     * Confirms the passages the decode would apply against the stem at playback level,
     * and keeps a part to two alternate patches, counting switches already in the journal.
     */
    private static PartReport confirmed(PartReport report, List<ReconstructionAllocation.Entry> entries, Path stem,
                                        VoiceWindows windows, VoicePatchShortlist screening, ReconstructionEdits journal,
                                        InstrumentCatalogue catalogue) throws IOException {
        var ids = new HashSet<String>();
        for (var entry : entries) ids.add(entry.note().id());
        var alternates = new java.util.LinkedHashSet<String>();
        for (var edit : journal.intents())
            if (edit.kind() == ReconstructionEdits.Kind.noteVoicing && edit.json().has("instrument") && !edit.inactive()
                    && edit.noteIds().stream().anyMatch(ids::contains))
                alternates.add(catalogue.match(edit.json().get("instrument").getAsString()).variant().assetId());
        var passages = new ArrayList<Passage>();
        var ranked = report.passages().stream().filter(passage -> !passage.confidence().equals("rejected"))
                .sorted(Comparator.comparingDouble((Passage passage) -> -passage.meanGain() * passage.onsets())).toList();
        var allowed = new HashSet<Passage>();
        for (var passage : ranked) {
            String asset = catalogue.match(passage.variantId()).variant().assetId();
            if (alternates.contains(asset) || alternates.size() < MAX_ALTERNATES) {
                alternates.add(asset);
                allowed.add(passage);
            }
        }
        for (var passage : report.passages()) {
            if (passage.confidence().equals("rejected")) {
                passages.add(passage);
                continue;
            }
            if (!allowed.contains(passage)) {
                passages.add(passage.with(new Guard("two alternates", false,
                        "the part already has " + MAX_ALTERNATES + " better supported alternate patches"), "rejected"));
                continue;
            }
            if (!passage.high()) {
                passages.add(passage);
                continue;
            }
            // The decode's scores come from full-volume templates. Before a passage applies, the two
            // patches meet on the passage's own exposed attacks at the level the stem plays them.
            var own = entries.stream().filter(entry -> passage.noteIds().contains(entry.note().id())).toList();
            boolean wins = screening.attackWinner(List.of(new VoicePatchShortlist.RankedPatch(passage.variantId(), 0)),
                    new VoicePatchShortlist.RankedPatch(report.home(), 0), own, stem, windows, PASSAGE_BLOCK_SECONDS) != null;
            passages.add(passage.with(new Guard("stem attacks at playback level", wins, wins
                    ? "closer than the part's own patch in most " + (int) PASSAGE_BLOCK_SECONDS + " s blocks"
                    : "too few exposed attacks, or no consistent win"), "review"));
        }
        return report.withPassages(List.copyOf(passages));
    }

    static PartReport report(int part, String name, String stem, boolean isolated, int notes, VoiceNoteDecode.Result decoded,
                             ReconstructionVoicing.SourceSound sound, InstrumentCatalogue catalogue,
                             java.util.Map<String, String> lanes, java.util.Set<String> homes, double minSeconds,
                             boolean withLabels) {
        return report(part, name, stem, isolated, notes, decoded, sound, catalogue, lanes, homes, minSeconds, withLabels,
                java.util.Set.of());
    }

    static PartReport report(int part, String name, String stem, boolean isolated, int notes, VoiceNoteDecode.Result decoded,
                             ReconstructionVoicing.SourceSound sound, InstrumentCatalogue catalogue,
                             java.util.Map<String, String> lanes, java.util.Set<String> homes, double minSeconds,
                             boolean withLabels, java.util.Set<String> locked) {
        return report(part, name, stem, isolated, notes, decoded, sound, catalogue, lanes, homes, minSeconds, withLabels,
                locked, new double[0]);
    }

    static PartReport report(int part, String name, String stem, boolean isolated, int notes, VoiceNoteDecode.Result decoded,
                             ReconstructionVoicing.SourceSound sound, InstrumentCatalogue catalogue,
                             java.util.Map<String, String> lanes, java.util.Set<String> homes, double minSeconds,
                             boolean withLabels, java.util.Set<String> locked, double[] sections) {
        var scored = decoded.notes();
        int candidates = decoded.candidates().size();
        if (scored.isEmpty()) return new PartReport(part, name, stem, isolated, notes, 0, sound.fm().variantId(),
                voice(catalogue, sound.fm().variantId()), 0, null, null, 0, 0, List.of(), null, null, null);
        int home = 0; // The decode lists the part's own patch first.
        var totals = new double[candidates];
        var wins = new int[candidates];
        var noteLabels = withLabels ? new ArrayList<NoteLabel>() : null;
        for (int i = 0; i < scored.size(); i++) {
            var note = scored.get(i);
            int best = 0;
            for (int k = 0; k < candidates; k++) {
                totals[k] += note.scores()[k];
                if (note.scores()[k] > note.scores()[best]) best = k;
            }
            wins[best]++;
            if (withLabels) noteLabels.add(new NoteLabel(note.note().id(), note.note().start(), note.note().end(),
                    note.note().pitch(), decoded.candidates().get(decoded.labels()[i]), decoded.candidates().get(best)));
        }
        int rank = 1, mode = 0;
        for (int k = 0; k < candidates; k++) {
            if (totals[k] > totals[home]) rank++;
            if (wins[k] > wins[mode]) mode = k;
        }
        var counts = new int[candidates];
        for (int label : decoded.labels()) counts[label]++;
        int majority = 0;
        for (int k = 1; k < candidates; k++) if (counts[k] > counts[majority]) majority = k;

        var passages = new ArrayList<Passage>();
        var labels = decoded.labels();
        for (int from = 0; from < labels.length; ) {
            int to = from;
            while (to < labels.length && labels[to] == labels[from]) to++;
            if (labels[from] != home)
                passages.add(passage(part, decoded, from, to, home, isolated, catalogue, lanes, homes, minSeconds,
                        wins[mode] / (double) scored.size(), locked, sections));
            from = to;
        }
        return new PartReport(part, name, stem, isolated, notes, scored.size(), sound.fm().variantId(),
                voice(catalogue, sound.fm().variantId()), rank, decoded.candidates().get(majority),
                voice(catalogue, decoded.candidates().get(majority)), wins[mode] / (double) scored.size(),
                decoded.switches(), List.copyOf(passages), noteLabels, withLabels ? decoded.candidates() : null, null);
    }

    private static Passage passage(int part, VoiceNoteDecode.Result decoded, int from, int to, int home, boolean isolated,
                                   InstrumentCatalogue catalogue, java.util.Map<String, String> lanes,
                                   java.util.Set<String> homes, double minSeconds, double modeShare,
                                   java.util.Set<String> locked, double[] sections) {
        var notes = decoded.notes();
        int label = decoded.labels()[from];
        var guards = new ArrayList<Guard>();
        var onsets = new java.util.TreeSet<Long>();
        double voiced = 0, reach = Double.NEGATIVE_INFINITY, gain = 0, weight = 0;
        for (int i = from; i < to; i++) {
            var note = notes.get(i).note();
            // Only notes on an FM lane can take the patch, so only they count toward the dwell.
            if (lanes.containsKey(note.id())) {
                onsets.add(Math.round(note.start() * 1000 / 30)); // A chord is one key-on.
                double begin = Math.max(note.start(), reach);
                if (note.end() > begin) voiced += note.end() - begin;
                reach = Math.max(reach, note.end());
            }
            gain += notes.get(i).weight() * (notes.get(i).scores()[label] - notes.get(i).scores()[home]);
            weight += notes.get(i).weight();
        }
        double mean = weight > 0 ? gain / weight : 0;
        boolean dwell = voiced >= minSeconds && onsets.size() >= MIN_ONSETS;
        guards.add(new Guard("dwell", dwell, format("%.1f s sounding over %d key-ons (needs %.0f s and %d)",
                voiced, onsets.size(), minSeconds, MIN_ONSETS)));

        // The part must also stay on its own patch long enough on each side, unless the part starts or ends there.
        double before = dwell(decoded, from - 1, -1, home), after = dwell(decoded, to, 1, home);
        boolean homeDwell = (from == 0 || before >= minSeconds) && (to == notes.size() || after >= minSeconds);
        guards.add(new Guard("home dwell", homeDwell, format("%.1f s before, %.1f s after", before, after)));

        // Two disjoint stretches of the passage must each prefer the patch on their own.
        int middle = from;
        double half = (notes.get(from).note().start() + notes.get(to - 1).note().start()) / 2;
        while (middle < to && notes.get(middle).note().start() < half) middle++;
        double first = meanGain(notes, from, middle, label, home), second = meanGain(notes, middle, to, label, home);
        boolean phrases = middle > from && middle < to && first >= MARGIN && second >= MARGIN
                && best(notes, from, middle, decoded.candidates().size()) == label
                && best(notes, middle, to, decoded.candidates().size()) == label;
        guards.add(new Guard("separate phrases", phrases, format("gain %.3f then %.3f per note (needs %.2f)", first, second, MARGIN)));

        double chance = chance(notes, from, to, label, home, part, sections);
        guards.add(new Guard("no-switch null", chance <= NULL_LEVEL,
                format("%.3f of parts reshuffled within section and register hold a run this strong, for any patch and length (needs %.2f)",
                        chance, NULL_LEVEL)));

        double attack = 0, sustain = 0;
        int sustained = 0;
        for (int i = from; i < to; i++) {
            attack += (notes.get(i).attack()[label] - notes.get(i).attack()[home]) / (to - from);
            if (!Float.isNaN(notes.get(i).sustain()[label])) {
                sustain += notes.get(i).sustain()[label] - notes.get(i).sustain()[home];
                sustained++;
            }
        }
        if (sustained > 0) sustain /= sustained;
        // A passage of attacks alone has nothing to say about the held sound, so it cannot apply unseen.
        guards.add(new Guard("attack and sustain", attack >= MARGIN && sustained >= MIN_HELD && sustain >= 0,
                sustained == 0 ? format("attack gain %.3f, no held notes (needs %d)", attack, MIN_HELD)
                        : format("attack gain %.3f, sustain gain %.3f over %d held notes (needs %d)",
                                attack, sustain, sustained, MIN_HELD)));
        boolean explained = modeShare >= EXPLAINED_SHARE;
        guards.add(new Guard("bank explains stem", explained, format("one patch is the best match for %.0f%% of notes (needs %.0f%%)",
                100 * modeShare, 100 * EXPLAINED_SHARE)));
        boolean free = true;
        for (int i = from; i < to; i++) if (locked.contains(notes.get(i).note().id())) free = false;
        guards.add(new Guard("no user choice", free, free ? "no note here has an assigned voice, instrument edit or scoped patch"
                : "a note here already has a voice someone chose"));
        guards.add(new Guard("own stem", isolated, isolated ? "the part has its own stem"
                : "shared stem, sung lead or whole-part voices"));

        String variant = decoded.candidates().get(label);
        var used = new HashSet<String>();
        for (int i = from; i < to; i++) if (lanes.containsKey(notes.get(i).note().id())) used.add(lanes.get(notes.get(i).note().id()));
        int bank = homes.contains(catalogue.match(variant).variant().assetId()) ? 0 : 25;
        // One command to enter on each lane, and one to return unless the part ends there.
        int commands = 2 * used.size() * (to == notes.size() ? 1 : 2);
        boolean all = guards.stream().allMatch(Guard::pass);
        // Review needs the evidence itself to stand: long enough, in both halves, and beyond chance.
        String confidence = all ? "high" : dwell && phrases && chance <= NULL_LEVEL && explained && free ? "review" : "rejected";
        var ids = new ArrayList<String>();
        for (int i = from; i < to; i++) if (lanes.containsKey(notes.get(i).note().id())) ids.add(notes.get(i).note().id());
        if (ids.isEmpty()) confidence = "rejected";
        return new Passage(part, variant, voice(catalogue, variant), notes.get(from).note().start(),
                notes.get(to - 1).note().end(), onsets.size(), voiced, mean, List.copyOf(guards), confidence,
                bank, commands, null, List.copyOf(ids));
    }

    /** Sounding time of the part's own patch next to a passage. Chord members count once. */
    private static double dwell(VoiceNoteDecode.Result decoded, int at, int step, int home) {
        var spans = new ArrayList<double[]>();
        for (int i = at; i >= 0 && i < decoded.notes().size() && decoded.labels()[i] == home; i += step) {
            var note = decoded.notes().get(i).note();
            spans.add(new double[]{note.start(), note.end()});
        }
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        double seconds = 0, reach = Double.NEGATIVE_INFINITY;
        for (var span : spans) {
            double begin = Math.max(span[0], reach);
            if (span[1] > begin) seconds += span[1] - begin;
            reach = Math.max(reach, span[1]);
        }
        return seconds;
    }

    private static double meanGain(List<VoiceNoteDecode.Scored> notes, int from, int to, int label, int home) {
        double gain = 0, weight = 0;
        for (int i = from; i < to; i++) {
            gain += notes.get(i).weight() * (notes.get(i).scores()[label] - notes.get(i).scores()[home]);
            weight += notes.get(i).weight();
        }
        return weight > 0 ? gain / weight : 0;
    }

    private static int best(List<VoiceNoteDecode.Scored> notes, int from, int to, int candidates) {
        var totals = new double[candidates];
        for (int i = from; i < to; i++)
            for (int k = 0; k < candidates; k++) totals[k] += notes.get(i).weight() * notes.get(i).scores()[k];
        int best = 0;
        for (int k = 1; k < candidates; k++) if (totals[k] > totals[best]) best = k;
        return best;
    }

    /**
     * How often the part, with no change of patch in it, would hold a run as strong as this
     * passage. Under that reading a note's evidence depends on where it sits in the form and
     * in the register, not on when it is played, so evidence is exchanged only among notes of
     * one section and one register band, in blocks of about a second that keep the local
     * correlation of real playing. Every other note stays where it is. Each reshuffled part
     * is then searched again in full: the strongest run of any length, for any candidate
     * patch. That pays for having searched patches, positions and lengths to find this one.
     * A patch that is better across a whole section or register scores as well reshuffled as
     * it does here, and a patch that is better everywhere scores higher. Neither is a switch.
     */
    static double chance(List<VoiceNoteDecode.Scored> notes, int from, int to, int label, int home, int part,
                         double[] sections) {
        int candidates = notes.getFirst().scores().length;
        // Positions of each stratum in playing order, cut into blocks.
        var strata = new java.util.LinkedHashMap<Long, List<List<Integer>>>();
        var opened = new java.util.HashMap<Long, Double>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i).note();
            int section = 0;
            while (section < sections.length && sections[section] <= note.start()) section++;
            long stratum = section * 1000L + note.pitch() / REGISTER_BAND;
            var blocks = strata.computeIfAbsent(stratum, key -> new ArrayList<>());
            Double began = opened.get(stratum);
            if (began == null || note.start() - began >= NULL_BLOCK_SECONDS) {
                blocks.add(new ArrayList<>());
                opened.put(stratum, note.start());
            }
            blocks.getLast().add(i);
        }
        double observed = 0;
        for (int i = from; i < to; i++) observed += gain(notes.get(i), label, home);
        int reached = 0;
        var random = new Random(31L * part + from);
        var order = new int[notes.size()];
        for (int trial = 0; trial < NULL_SHUFFLES; trial++) {
            for (var blocks : strata.values()) {
                var slots = new ArrayList<Integer>();
                for (var block : blocks) slots.addAll(block);
                var shuffled = new ArrayList<>(blocks);
                java.util.Collections.shuffle(shuffled, random);
                int at = 0;
                for (var block : shuffled) for (int source : block) order[slots.get(at++)] = source;
            }
            boolean matched = false;
            for (int k = 0; k < candidates && !matched; k++) {
                if (k == home) continue;
                double run = 0;
                for (int i = 0; i < order.length && !matched; i++) {
                    run = Math.max(0, run) + gain(notes.get(order[i]), k, home);
                    matched = run >= observed;
                }
            }
            if (matched) reached++;
        }
        return (reached + 1.0) / (NULL_SHUFFLES + 1);
    }

    private static double gain(VoiceNoteDecode.Scored note, int candidate, int home) {
        return note.weight() * (note.scores()[candidate] - note.scores()[home]);
    }

    private static JsonObject edit(Passage passage, double gain) {
        var edit = new JsonObject();
        edit.addProperty("id", GROUP + "-" + ReconstructionArtifacts.fingerprint(
                List.of(passage.part(), passage.noteIds().getFirst(), passage.variantId())).substring(0, 16));
        edit.addProperty("op", "noteVoicing");
        edit.addProperty("author", "agent");
        edit.addProperty("reason", format("%s explains %.1f s of part %d from %.2f s better than the part's own patch",
                passage.voice(), passage.voicedSeconds(), passage.part(), passage.start()));
        edit.addProperty("group", GROUP);
        edit.add("noteIds", JSON.toJsonTree(passage.noteIds()));
        edit.addProperty("laneType", "FM");
        edit.addProperty("instrument", passage.variantId());
        edit.addProperty("gain", gain);
        var evidence = new JsonObject();
        evidence.addProperty("text", passage.guards().stream()
                .map(guard -> (guard.pass() ? "pass " : "fail ") + guard.name() + ": " + guard.detail())
                .collect(java.util.stream.Collectors.joining("; "))
                + format("; about %d bank and %d command bytes", passage.bankBytes(), passage.switchBytes()));
        evidence.addProperty("path", FILE);
        edit.add("evidence", evidence);
        return edit;
    }

    private static String voice(InstrumentCatalogue catalogue, String variant) {
        var match = catalogue.match(variant);
        var labels = InstrumentCatalogue.labels(match.asset());
        return labels.isEmpty() ? match.variant().name() : labels.getFirst().ref() + " “" + labels.getFirst().text() + "”";
    }

    private static String format(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }
}
