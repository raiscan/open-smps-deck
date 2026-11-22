package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Read-only data and chip audition for Studio's reconstruction edit controls. */
public final class ReconstructionEditing {
    private static final int[] F_NUMBERS = {644, 683, 723, 766, 813, 860, 911, 965, 1023, 1084, 1148, 1216};

    public record Attack(double start, double end, int pitch, boolean drums, boolean single,
                         List<ReconstructionInspection.BendPoint> bend) {}

    public record Instrument(ReconstructionLaneType type, String id, String name) {
        @Override public String toString() { return name + " · " + id; }
    }

    public record Vibrato(double hz, double cents, double minCents, double maxCents, int rate, int delta, int steps) {}

    public record Audition(StudioProject project, double start, double end) {}

    private final ReconstructionStages.Score score;
    private final Map<String, ReconstructionStages.Note> notes = new HashMap<>();
    private final Map<Integer, Boolean> drums = new HashMap<>();
    private final ReconstructionVoicing.Result voicing;
    private final List<Instrument> instruments;

    private ReconstructionEditing(ReconstructionStages.Score score, ReconstructionVoicing.Result voicing,
                                  InstrumentCatalogue catalogue) {
        this.score = score;
        this.voicing = voicing;
        for (var note : score.notes()) notes.put(note.id(), note);
        for (var part : score.source().parts()) drums.put(part.id(), part.drums());
        var options = new java.util.ArrayList<Instrument>(List.of(
                new Instrument(ReconstructionLaneType.NOISE, "fixed", "Fixed white noise"),
                new Instrument(ReconstructionLaneType.NOISE, "closed-hat", "Closed hi-hat"),
                new Instrument(ReconstructionLaneType.NOISE, "cymbal", "Decaying cymbal")));
        if (catalogue != null) for (var variant : catalogue.variants()) {
            var type = switch (catalogue.asset(variant.assetId()).kind()) {
                case FM_VOICE -> ReconstructionLaneType.FM;
                case PSG_VOLUME -> ReconstructionLaneType.PSG;
                case DAC_SAMPLE -> ReconstructionLaneType.DAC;
                default -> null;
            };
            if (type != null) options.add(new Instrument(type, variant.id(), variant.name()));
        }
        instruments = options.stream().sorted(Comparator.comparing(Instrument::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Instrument::id)).toList();
    }

    public static ReconstructionEditing open(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var cataloguePath = Path.of(score.inputs().catalogue());
        var catalogue = Files.isRegularFile(cataloguePath) ? InstrumentCatalogue.open(cataloguePath, score.inputs().targetGame()) : null;
        return new ReconstructionEditing(score,
                ReconstructionTrace.optional(work, "voicing.json", ReconstructionVoicing.Result.class), catalogue);
    }

    public List<Instrument> instruments() { return instruments; }

    public Attack attack(String id) {
        var note = notes.get(id);
        if (note == null) return null;
        var bend = note.bend() == null ? List.<ReconstructionInspection.BendPoint>of() : note.bend().points().stream()
                .map(point -> new ReconstructionInspection.BendPoint(point.time(), note.pitch() + point.cents() / 100.0))
                .toList();
        return new Attack(note.start(), note.end(), note.pitch(), drums.getOrDefault(note.part(), false),
                single(note), bend);
    }

    public String laneIssue(ReconstructionInspection.Overlay overlay, ReconstructionLaneType type, String lane) {
        var note = notes.getOrDefault(overlay.id(), new ReconstructionStages.Note(overlay.id(), overlay.part(),
                overlay.interval().sourceStart(), overlay.interval().sourceEnd(), overlay.pitch(), 96, "edit", 1));
        boolean percussion = drums.getOrDefault(overlay.part(), false);
        boolean fmOnly = score.inputs().fmOnly();
        var profile = score.inputs().profile();
        if (lane != null && !ReconstructionEdits.pinCompatible(note, percussion, lane, fmOnly, profile))
            return lane + " cannot play this note's part, pitch or attack in the current chip configuration";
        boolean authoredBend = note.bend() != null && note.bend().editReason() != null
                || overlay.fate().startsWith("Pending") && !overlay.bend().isEmpty() && note.bend() == null;
        if (authoredBend && (lane != null && lane.startsWith("PSG")
                || type == ReconstructionLaneType.PSG || type == ReconstructionLaneType.NOISE))
            return "Authored bends require FM";
        return type == null ? null : ReconstructionEdits.laneTypeIssue(note, percussion, type, fmOnly, profile);
    }

    private static boolean single(ReconstructionStages.Note note) {
        return note.run() == null && !"external".equals(note.source());
    }

    public int detune(String lane) {
        var settings = voicing == null || lane == null ? null : voicing.lanes().get(lane);
        return settings == null ? 0 : settings.effects().detune();
    }

    public static Vibrato vibrato(int pitch, int detune, double hz, double cents) {
        double frequency = Math.clamp(hz, 30.0 / 254, 15);
        int steps = 2;
        for (int candidate = 4; candidate <= 254; candidate += 2)
            if (Math.abs(30.0 / candidate - frequency) < Math.abs(30.0 / steps - frequency)) steps = candidate;
        int fNumber = F_NUMBERS[Math.floorMod(pitch, 12)] + detune;
        int half = steps / 2;
        int headroom = Math.min(fNumber - 1, 2047 - fNumber);
        double desired = fNumber * Math.tanh(Math.max(0, cents) * Math.log(2) / 1200);
        int bestRate = 1, bestDelta = 0, max = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int rate = 1; rate <= half; rate++) {
            if (half % rate != 0) continue;
            int units = half / rate;
            int limit = Math.min(127, headroom / units);
            if (limit < 1) continue;
            int delta = cents <= 0 ? 0 : (int) Math.clamp(Math.round(desired / units), 1, limit);
            double error = Math.abs(depth(fNumber, delta * units) - cents);
            max = Math.max(max, limit * units);
            if (error < nearest) {
                nearest = error;
                bestRate = rate;
                bestDelta = delta;
            }
        }
        return new Vibrato(30.0 / steps, depth(fNumber, bestDelta * (half / bestRate)), depth(fNumber, 1),
                depth(fNumber, max), bestRate, bestDelta, steps);
    }

    public Vibrato vibrato(int pitch, String lane, int rate, int delta, int steps) {
        int fNumber = F_NUMBERS[Math.floorMod(pitch, 12)] + detune(lane);
        int amplitude = Math.min(Math.min(fNumber - 1, 2047 - fNumber), Math.abs(delta) * steps / (2 * rate));
        var range = vibrato(pitch, detune(lane), 30.0 / steps, 0);
        return new Vibrato(30.0 / steps, depth(fNumber, amplitude), range.minCents(), range.maxCents(), rate, delta, steps);
    }

    private static double depth(int fNumber, int amplitude) {
        return 600 * Math.log((fNumber + amplitude) / (double) (fNumber - amplitude)) / Math.log(2);
    }

    /** Assemble just the selected, allocated attacks with the candidate instrument, without publishing. */
    public static Audition audition(Path work, Set<String> noteIds, Instrument candidate) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var timeline = ReconstructionTimeMap.of(score);
        var allocation = ReconstructionArtifacts.read(work.resolve("allocation.json"), ReconstructionAllocation.Result.class);
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        var catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()), score.inputs().targetGame());
        var scopes = ReconstructionVoicing.validate(voicing, allocation, catalogue, timeline);
        var candidateAssignment = new ReconstructionVoicePlan.Assignment(candidate.type(), candidate.id());
        candidateAssignment.validate(catalogue, "Audition");
        var selected = allocation.notes().stream().filter(entry -> entry.lane() != null && noteIds.contains(entry.note().id())).toList();
        require(!selected.isEmpty(), "Audition", "selection has no allocated notes");
        var assignments = new HashMap<String, ReconstructionVoicePlan.Assignment>();
        for (var entry : selected) {
            require(candidate.type().accepts(entry),
                    "Audition", "apply a compatible lane type before auditioning " + candidate.type());
            assignments.put(entry.note().id(), candidateAssignment);
        }
        var solo = new ReconstructionAllocation.Result(allocation.version(), selected);
        var structure = ReconstructionStages.readStructure(work.resolve("structure.json"), score);
        var matches = ReconstructionCli.voicedMatches(catalogue, solo, voicing);
        var result = S3kReconstruction.arrangeAllocated("Selection", score.inputs().ticksPerBeat(), timeline.ticks(),
                new S3kReconstruction.Score(score.source().parts(), List.of(), List.of(), score.profile()), solo, structure, catalogue,
                matches.byLane(), Map.of(), voicing.lanes(), false, true, scopes, Map.of(), Map.of(), null, assignments,
                voicing.sourceInstruments());
        int start = selected.stream().mapToInt(ReconstructionAllocation.Entry::startTick).min().orElseThrow();
        int end = selected.stream().mapToInt(ReconstructionAllocation.Entry::endTick).max().orElseThrow();
        var project = result.project();
        return new Audition(project, timeline.secondsAtTick(start),
                Math.min(timeline.frames() / 60.0, timeline.secondsAtTick(end + 30)));
    }
}
