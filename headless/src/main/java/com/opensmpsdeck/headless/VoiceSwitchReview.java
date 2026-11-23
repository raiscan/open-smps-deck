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
    private static final int NULL_SHUFFLES = 200;
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
    }

    /** The decoded patch and the note's own best patch, for measurement. */
    record NoteLabel(String id, double start, double end, int pitch, String decoded, String nearest) {}

    record PartReport(int part, String name, String stem, boolean isolated, int notes, int scored, String home,
                      String homeVoice, int homeRank, String majority, String majorityVoice, double modeShare,
                      int switches, List<Passage> passages, List<NoteLabel> labels, List<String> candidates,
                      Boolean majorityWinsAttacks) {
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
                "reconstruct voice-switches expects WORK_DIR [--part ID] [--min-run SECONDS] [--no-figures] [--labels] [--propose | --apply]");
        Integer part = null;
        boolean labels = false;
        double minSeconds = DEFAULT_MIN_SECONDS;
        boolean figures = true;
        Mode mode = Mode.off;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--part" -> part = Integer.parseInt(args[++i]);
                case "--min-run" -> minSeconds = Double.parseDouble(args[++i]);
                case "--no-figures" -> figures = false;
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
     */
    public static JsonObject review(Path work, Options options) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        require(voicing.sourceInstruments() != null, GROUP, "voicing has no source instruments to review");
        var catalogue = InstrumentCatalogue.openReconstruction(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
        var time = ReconstructionTimeMap.of(score);
        var journal = ReconstructionEdits.read(work);
        var known = new HashSet<String>();
        var assigned = new HashSet<String>();
        for (var edit : journal.intents()) {
            known.add(edit.id());
            if (edit.kind() == ReconstructionEdits.Kind.noteVoicing && edit.json().has("instrument")) assigned.addAll(edit.noteIds());
        }
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
        var applied = new JsonArray();
        for (var part : score.source().parts()) {
            if (options.part() != null && options.part() != part.id()) continue;
            var sound = voicing.sourceInstruments().get(ReconstructionVoicePlan.SourceInstrument.id(part.id()));
            String stem = score.inputs().stems().get(part.id());
            if (sound == null || sound.fm().variantId() == null || stem == null || stem.equals(score.inputs().wav())) continue;
            // Two parts on one stem, or the mix itself, cannot say which part changed.
            boolean isolated = score.inputs().stems().values().stream().filter(stem::equals).count() == 1
                    && !ReconstructionVocalLead.isVocal(score.inputs(), part.id()) && !score.inputs().wholePartVoices();
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
                    homes, options.minSeconds(), options.labels());
            if (report.majority() != null && !report.majority().equals(report.home())) {
                var entries = placement.notes().stream().filter(entry -> entry.note().part() == part.id()
                        && entry.lane() != null && entry.lane().startsWith("FM") && entry.mergedInto() == null).toList();
                report = report.withAttacks(!entries.isEmpty() && screening.attackWinner(
                        List.of(new VoicePatchShortlist.RankedPatch(report.majority(), 0)),
                        new VoicePatchShortlist.RankedPatch(report.home(), 0), entries, Path.of(stem), windows) != null);
            }
            reports.add(report);
            for (var passage : report.passages()) {
                if (passage.confidence().equals("rejected") || passage.noteIds().stream().anyMatch(assigned::contains)) continue;
                // Keep the passage as loud as the part's own patch would have played it.
                int pitch = decoded.notes().stream().filter(note -> passage.noteIds().contains(note.note().id()))
                        .mapToInt(note -> note.note().pitch()).sorted().skip(passage.noteIds().size() / 2).findFirst().orElse(60);
                double gain = sound.fm().gain() * Math.clamp(decoder.loudness(sound.fm().variantId(), pitch)
                        / Math.max(1e-9, decoder.loudness(passage.variantId(), pitch)), .5, 2);
                var edit = edit(passage, Math.round(Math.min(4, gain) * 1000) / 1000.0);
                if (known.contains(edit.get("id").getAsString())) continue;
                if (options.mode() == Mode.auto && passage.high()) applied.add(edit);
                else if (options.mode() != Mode.off) proposed.add(edit);
            }
        }
        var result = new JsonObject();
        result.add("parts", JSON.toJsonTree(reports));
        if (!applied.isEmpty() || !proposed.isEmpty())
            java.nio.file.Files.writeString(work.resolve(FILE), JSON.toJson(result));
        if (!applied.isEmpty()) result.add("applied", apply(work, applied, score.profile().bankLimitBytes()));
        if (!proposed.isEmpty())
            result.add("proposals", ReconstructionEditCommand.run(work, "propose", proposed, null, false, (done, total, message) -> {}));
        return result;
    }

    /**
     * Adds the edits, rebuilds, and checks the compiled bank. A build that fails or
     * overflows drops the last edit and tries again, so notes are never the price.
     */
    private static JsonObject apply(Path work, JsonArray edits, int bankLimit) throws IOException {
        var before = sizes(work);
        var result = new JsonObject();
        var dropped = new JsonArray();
        var kept = edits.deepCopy();
        while (!kept.isEmpty()) {
            String failure = null;
            try {
                ReconstructionEditCommand.run(work, "add", kept, null, true, (done, total, message) -> {});
                var after = sizes(work);
                if (after.get("bankBytes").getAsInt() > bankLimit) failure = "compiled bank exceeds " + bankLimit + " bytes";
                else {
                    result.add("before", before);
                    result.add("after", after);
                    break;
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                failure = String.valueOf(error.getMessage());
            }
            for (var edit : kept) {
                String id = edit.getAsJsonObject().get("id").getAsString();
                if (ReconstructionEdits.read(work).intents().stream().anyMatch(intent -> intent.id().equals(id)))
                    ReconstructionEditCommand.run(work, "remove", null, id, false, (done, total, message) -> {});
            }
            var last = kept.remove(kept.size() - 1).getAsJsonObject();
            last.addProperty("dropped", failure);
            dropped.add(last);
            if (kept.isEmpty())
                ReconstructionStages.run("run", new String[]{"--from", "assemble", work.toString()}, new PrintStream(java.io.OutputStream.nullOutputStream()), new PrintStream(java.io.OutputStream.nullOutputStream()));
        }
        result.add("edits", kept);
        if (!dropped.isEmpty()) result.add("dropped", dropped);
        return result;
    }

    private static JsonObject sizes(Path work) throws IOException {
        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        var sizes = new JsonObject();
        for (String key : List.of("bankBytes", "fmPatchBankBytes", "fmPatchSwitchBytes"))
            if (report.has(key)) sizes.add(key, report.get(key));
        return sizes;
    }

    static PartReport report(int part, String name, String stem, boolean isolated, int notes, VoiceNoteDecode.Result decoded,
                             ReconstructionVoicing.SourceSound sound, InstrumentCatalogue catalogue,
                             java.util.Map<String, String> lanes, java.util.Set<String> homes, double minSeconds,
                             boolean withLabels) {
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
                        wins[mode] / (double) scored.size()));
            from = to;
        }
        return new PartReport(part, name, stem, isolated, notes, scored.size(), sound.fm().variantId(),
                voice(catalogue, sound.fm().variantId()), rank, decoded.candidates().get(majority),
                voice(catalogue, decoded.candidates().get(majority)), wins[mode] / (double) scored.size(),
                decoded.switches(), List.copyOf(passages), noteLabels, withLabels ? decoded.candidates() : null, null);
    }

    private static Passage passage(int part, VoiceNoteDecode.Result decoded, int from, int to, int home, boolean isolated,
                                   InstrumentCatalogue catalogue, java.util.Map<String, String> lanes,
                                   java.util.Set<String> homes, double minSeconds, double modeShare) {
        var notes = decoded.notes();
        int label = decoded.labels()[from];
        var guards = new ArrayList<Guard>();
        var onsets = new java.util.TreeSet<Long>();
        double voiced = 0, reach = Double.NEGATIVE_INFINITY, gain = 0, weight = 0;
        for (int i = from; i < to; i++) {
            var note = notes.get(i).note();
            onsets.add(Math.round(note.start() * 1000 / 30)); // A chord is one key-on.
            double begin = Math.max(note.start(), reach);
            if (note.end() > begin) voiced += note.end() - begin;
            reach = Math.max(reach, note.end());
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

        double chance = chance(notes, from, to, label, home, part);
        guards.add(new Guard("no-switch null", chance <= NULL_LEVEL,
                format("%.3f of shuffled parts hold a passage this strong (needs %.2f)", chance, NULL_LEVEL)));

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
        guards.add(new Guard("attack and sustain", attack >= MARGIN && sustain >= 0,
                sustained == 0 ? format("attack gain %.3f, no held notes", attack)
                        : format("attack gain %.3f, sustain gain %.3f over %d held notes", attack, sustain, sustained)));
        boolean explained = modeShare >= EXPLAINED_SHARE;
        guards.add(new Guard("bank explains stem", explained, format("one patch is the best match for %.0f%% of notes (needs %.0f%%)",
                100 * modeShare, 100 * EXPLAINED_SHARE)));
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
        String confidence = all ? "high" : dwell && phrases && chance <= NULL_LEVEL && explained ? "review" : "rejected";
        var ids = new ArrayList<String>();
        for (int i = from; i < to; i++) if (lanes.containsKey(notes.get(i).note().id())) ids.add(notes.get(i).note().id());
        if (ids.isEmpty()) confidence = "rejected";
        return new Passage(part, variant, voice(catalogue, variant), notes.get(from).note().start(),
                notes.get(to - 1).note().end(), onsets.size(), voiced, mean, List.copyOf(guards), confidence,
                bank, commands, null, List.copyOf(ids));
    }

    private static double dwell(VoiceNoteDecode.Result decoded, int at, int step, int home) {
        double seconds = 0;
        for (int i = at; i >= 0 && i < decoded.notes().size() && decoded.labels()[i] == home; i += step) {
            var note = decoded.notes().get(i).note();
            seconds += note.end() - note.start();
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
     * How often a part with the same notes in shuffled order holds a run this strong.
     * One-second blocks keep the local correlation of real playing. A patch that is
     * simply better everywhere scores high here, and that is not a switch.
     */
    private static double chance(List<VoiceNoteDecode.Scored> notes, int from, int to, int label, int home, int part) {
        var gains = new double[notes.size()];
        var blocks = new ArrayList<List<Integer>>();
        long current = Long.MIN_VALUE;
        for (int i = 0; i < notes.size(); i++) {
            gains[i] = notes.get(i).weight() * (notes.get(i).scores()[label] - notes.get(i).scores()[home]);
            long block = (long) Math.floor(notes.get(i).note().start());
            if (block != current) blocks.add(new ArrayList<>());
            current = block;
            blocks.getLast().add(i);
        }
        double observed = 0;
        for (int i = from; i < to; i++) observed += gains[i];
        int length = to - from, reached = 0;
        var random = new Random(31L * part + from);
        var shuffled = new double[notes.size()];
        for (int trial = 0; trial < NULL_SHUFFLES; trial++) {
            java.util.Collections.shuffle(blocks, random);
            int at = 0;
            for (var block : blocks) for (int i : block) shuffled[at++] = gains[i];
            double window = 0, best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < shuffled.length; i++) {
                window += shuffled[i];
                if (i >= length) window -= shuffled[i - length];
                if (i >= length - 1) best = Math.max(best, window);
            }
            if (best >= observed) reached++;
        }
        return (reached + 1.0) / (NULL_SHUFFLES + 1);
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
