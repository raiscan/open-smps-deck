package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;

/** Source pitch continuity and independent playback evidence for written holds. */
final class ReconstructionHeldNotes {
    private static final double MIN_HOLD = .45;
    private static final double MIN_SOURCE_DB = -60;
    private static final double MAX_SOURCE_DECAY_DB = 24;
    private static final double MIN_RENDER_DB = -55;
    private static final double MAX_RENDER_DECAY_DB = 30;
    private static final int FFT_SIZE = 8192;
    private static final double[] WINDOW = new double[FFT_SIZE / 2];

    static {
        for (int i = 0; i < WINDOW.length; i++) WINDOW[i] = .5 - .5 * Math.cos(Math.TAU * i / WINDOW.length);
    }

    record Hold(double tailTime, double sourceTailDb, double sourceDecayDb) {
        double minimumDecayDb() { return Math.max(-MAX_RENDER_DECAY_DB, sourceDecayDb - 12); }
    }

    record Example(int pitch, double earlySeconds, double tailSeconds, Hold source) {}
    record Finding(String noteId, int part, int pitch, double sourceStart, double sourceTailTime,
                   String lane, String kind, double sourceTailDb, double sourceDecayDb, Double renderTailDb,
                   Double renderDecayDb, String proposal) {}
    record Report(int sourceHolds, List<Finding> findings) {}

    private ReconstructionHeldNotes() {}

    static Map<String, Hold> measure(ReconstructionStages.Score score, List<ReconstructionStages.Note> notes)
            throws IOException {
        var inputs = score.inputs();
        if (inputs == null || inputs.stems() == null || inputs.stems().isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Hold>();
        var fft = new LeadEffectMatcher.Spectrum(FFT_SIZE);
        var durations = new HashMap<Path, Double>();
        for (var note : notes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("hold analysis cancelled");
            String stem = inputs.stems().get(note.part());
            if (stem == null || stem.equals(inputs.wav()) || note.run() != null || note.end() - note.start() < MIN_HOLD)
                continue;
            double early = early(note);
            double tail = note.start() + .82 * (note.end() - note.start());
            Path path = Path.of(stem);
            if (!durations.containsKey(path)) durations.put(path, ReferenceAudio.durationMicros(path) / 1e6);
            if (tail + .1 >= durations.get(path)) continue;
            double initial = pitchDb(path, fft, early, note);
            double middle = pitchDb(path, fft, (early + tail) / 2, note);
            double ending = pitchDb(path, fft, tail, note);
            double decay = ending - Math.max(initial, middle);
            if (Math.min(middle, ending) >= MIN_SOURCE_DB && decay >= -MAX_SOURCE_DECAY_DB
                    && initial >= MIN_SOURCE_DB)
                result.put(note.id(), new Hold(tail, ending, decay));
        }
        return result;
    }

    private static double early(ReconstructionStages.Note note) {
        return note.start() + .12;
    }

    private static double pitchDb(Path path, LeadEffectMatcher.Spectrum fft, double time,
                                  ReconstructionStages.Note note) throws IOException {
        var audio = ReferenceAudio.evidenceRange(path, Math.max(0, time - .1), time + .1);
        // Analyze ~93 ms at 44.1 kHz. A wider bin window at low pitches
        // accommodates pitch bends and bass fundamentals close to bin centers.
        int at = (int) Math.round((time - audio.offset()) * audio.rate()) - WINDOW.length / 2;
        fft.transform(audio.samples(), at, WINDOW);
        double fundamental = EvidenceSpectrum.hz(note.pitch());
        double total = 0;
        for (int harmonic = 1; harmonic <= 4; harmonic++) {
            double center = fundamental * harmonic * FFT_SIZE / audio.rate();
            if (center >= FFT_SIZE / 2 - 4) break;
            int radius = Math.max(2, (int) Math.ceil(.032 * center));
            double peak = 0;
            for (int bin = Math.max(1, (int) Math.round(center) - radius);
                 bin <= Math.min(FFT_SIZE / 2 - 1, (int) Math.round(center) + radius); bin++)
                peak = Math.max(peak, fft.power(bin));
            total += peak;
        }
        return db(total * 8 / (WINDOW.length * (double) WINDOW.length));
    }

    static List<Example> examples(List<ReconstructionAllocation.Entry> entries, Map<String, Hold> holds) {
        var examples = new ArrayList<Example>();
        var pitches = new java.util.HashSet<Integer>();
        for (var entry : entries.stream().filter(n -> holds.containsKey(n.note().id()))
                .sorted(Comparator.comparingDouble((ReconstructionAllocation.Entry n) ->
                        holds.get(n.note().id()).tailTime() - n.note().start()).reversed()).toList()) {
            var note = entry.note();
            // Exclude tails already lost to allocation from the patch decision.
            if (holds.get(note.id()).tailTime() * 60 >= entry.endTick() || !pitches.add(note.pitch())) continue;
            var hold = holds.get(note.id());
            examples.add(new Example(note.pitch(), early(note) - note.start(), hold.tailTime() - note.start(), hold));
            if (examples.size() == 4) break;
        }
        return List.copyOf(examples);
    }

    static List<VoicePatchShortlist.RankedPatch> fittingPatches(InstrumentCatalogue catalogue,
            TargetProfile profile, List<VoicePatchShortlist.RankedPatch> candidates,
            List<Example> examples) throws IOException {
        if (examples.isEmpty() || candidates.size() < 2) return candidates;
        var compatible = new ArrayList<VoicePatchShortlist.RankedPatch>();
        double bestLoss = Double.POSITIVE_INFINITY;
        var best = candidates.getFirst();
        for (var candidate : candidates) {
            double loss = patchLoss(catalogue, profile, candidate.id(), examples, null);
            if (loss == 0) compatible.add(candidate);
            if (loss < bestLoss) {
                bestLoss = loss;
                best = candidate;
            }
        }
        return compatible.isEmpty() ? List.of(best) : List.copyOf(compatible);
    }

    static double patchLoss(InstrumentCatalogue catalogue, TargetProfile profile, String variant,
                            List<Example> examples, ReconstructionVoicing.FmTimbre timbre) throws IOException {
        double loss = 0;
        for (var example : examples) {
            // Use the pinned synth so carrier masks, key scaling, decay,
            // sustain level and release agree with the selected driver.
            var clock = profile.clock();
            double tail = Math.min(example.tailSeconds(), clock.secondsAtTick(176));
            int pitch = Math.clamp(example.pitch(), profile.fm().low(), profile.fm().high());
            int ticks = Math.clamp((int) clock.nearestTick((tail + .05) * 60), 1, 180);
            var raw = CataloguePlayback.fmTimbreProbe(catalogue, variant, pitch, ticks, profile.game(), timbre);
            var probe = new StudioProject(4, raw.name(), "S3K", raw.ticksPerBeat(),
                    raw.tempo(), raw.voices(), raw.sequences(), raw.clips(), raw.envelopes(), raw.samples(),
                    profile.game(), profile.game() == TargetGame.S3K ? null : clock.tempo());
            double[] levels = power(TargetPlayback.prepare(probe),
                    Math.max(1, (int) clock.frameAtTick(ticks)), S3kRenderer.ChannelMask.NONE);
            double end = windowDb(levels, tail);
            double decay = end - windowDb(levels, example.earlySeconds());
            loss += Math.max(0, example.source().minimumDecayDb() - decay)
                    + Math.max(0, MIN_RENDER_DB - end);
        }
        return loss;
    }

    static double db(double power) { return 10 * Math.log10(Math.max(1e-12, power)); }

    static Report inspect(ReconstructionStages.Score score, ReconstructionAllocation.Result allocation,
                          S3kReconstruction.Result assembled) throws IOException {
        var timeline = ReconstructionTimeMap.of(score);
        var candidates = allocation.notes().stream()
                .filter(entry -> entry.role() != Role.drums && entry.role() != Role.drop)
                .filter(entry -> entry.mergedInto() == null)
                .map(entry -> timeline.sourceNote(entry.note())).toList();
        var holds = measure(score, candidates);
        if (holds.isEmpty()) return new Report(0, List.of());
        var gates = new HashMap<String, List<S3kReconstruction.VoicedGate>>();
        for (var gate : assembled.voicedGates()) gates.computeIfAbsent(gate.id(), ignored -> new ArrayList<>()).add(gate);
        var power = new HashMap<String, double[]>();
        var findings = new ArrayList<Finding>();
        TargetPlayback playback = null;
        for (var note : candidates) {
            var hold = holds.get(note.id());
            if (hold == null) continue;
            var voiced = gates.getOrDefault(note.id(), List.of());
            double tail = timeline.project(hold.tailTime());
            var gate = voiced.stream().filter(row -> timeline.secondsAtTick(row.startTick()) <= tail
                    && timeline.secondsAtTick(row.endTick()) > tail + .025).findFirst().orElse(null);
            if (gate == null) {
                String lane = voiced.isEmpty() ? null : voiced.getLast().lane();
                findings.add(finding(note, hold, lane, voiced.isEmpty() ? "missing" : "truncated", null, null,
                        "Review allocation and restore the held gate on an available lane."));
                continue;
            }
            if (playback == null) playback = TargetPlayback.prepare(assembled.project());
            var levels = power.get(gate.lane());
            if (levels == null) {
                levels = power(playback, timeline.frames(), ReconstructionView.soloMask(List.of(gate.lane())));
                power.put(gate.lane(), levels);
            }
            double start = timeline.secondsAtTick(gate.startTick());
            double openAt = Math.clamp(timeline.project(early(note)), start + .025, Math.max(start + .025, tail - .025));
            double open = windowDb(levels, openAt);
            double end = windowDb(levels, tail);
            double decay = end - open;
            if (end < MIN_RENDER_DB || decay < hold.minimumDecayDb())
                findings.add(finding(note, hold, gate.lane(), "held-but-silent", end, decay,
                        "Audition a sustaining " + (gate.lane().startsWith("PSG") ? "PSG envelope." : "FM patch for this source instrument.")));
        }
        return new Report(holds.size(), List.copyOf(findings));
    }

    private static Finding finding(ReconstructionStages.Note note, Hold hold, String lane, String kind,
                                   Double renderTail, Double renderDecay, String proposal) {
        return new Finding(note.id(), note.part(), note.pitch(), note.start(), hold.tailTime(), lane, kind,
                hold.sourceTailDb(), hold.sourceDecayDb(), renderTail, renderDecay, proposal);
    }

    static double[] power(TargetPlayback playback, int frames, S3kRenderer.ChannelMask mask) throws IOException {
        var levels = new double[frames];
        int[] at = {0};
        playback.render(frames, mask, pcm -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("hold probe cancelled");
            double squares = 0, mean = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                double sample = (pcm[i] + (double) pcm[i + 1]) / 65536;
                mean += sample;
                squares += sample * sample;
            }
            int count = pcm.length / 2;
            levels[at[0]++] = Math.max(0, squares / count - Math.pow(mean / count, 2));
        });
        return levels;
    }

    static double windowDb(double[] levels, double seconds) {
        int frame = (int) Math.round(seconds * 60);
        int start = Math.max(0, frame - 1), end = Math.min(levels.length, frame + 2);
        double total = 0;
        for (int at = start; at < end; at++) total += levels[at];
        return db(total / Math.max(1, end - start));
    }

    static Report read(Path work) throws IOException {
        var report = work.resolve("report.json");
        if (!java.nio.file.Files.isRegularFile(report)) return null;
        var json = ReconstructionArtifacts.readTree(report).getAsJsonObject();
        if (!json.has("heldNotes")) return null;
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var allocation = ReconstructionTrace.optional(work, "allocation.json", ReconstructionAllocation.Result.class);
        var voicing = ReconstructionTrace.optional(work, "voicing.json", ReconstructionVoicing.Result.class);
        var gson = new com.google.gson.Gson();
        if (allocation == null || voicing == null || !gson.toJsonTree(ReconstructionCli.assemblyInputs(score, allocation, voicing))
                .equals(json.get("assemblyInputs"))) return null;
        return gson.fromJson(json.get("heldNotes"), Report.class);
    }
}
