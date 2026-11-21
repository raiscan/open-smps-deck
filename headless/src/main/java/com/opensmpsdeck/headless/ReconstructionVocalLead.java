package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionLeadAudio.HOP;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;

final class ReconstructionVocalLead {
    // Analysis limits of the pitch tracker. The usable register is learned from each stem.
    private static final int LOW = ReconstructionLeadAudio.LOW, HIGH = ReconstructionLeadAudio.HIGH;
    private static final int STATES = HIGH - LOW + 2;

    private ReconstructionVocalLead() {}

    static boolean isVocal(ReconstructionStages.Inputs inputs, int part) {
        var source = inputs.audioOnly();
        var stem = source == null ? null : source.stems().get(part);
        return stem != null && "vocals".equals(stem.transcriberRole());
    }

    static int[] contour(ReconstructionLeadAudio.Track track) {
        return smooth(periodicContour(track));
    }

    private static int[] periodicContour(ReconstructionLeadAudio.Track track) {
        int[] raw = new int[track.frames()];
        var distances = new double[128];
        for (int frame = 0; frame < raw.length; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("vocal contour cancelled");
            raw[frame] = track.periodicPitch(frame, distances);
        }
        return raw;
    }

    private static int[] smooth(int[] raw) {
        var stable = new int[raw.length];
        for (int frame = 0; frame < raw.length; frame++) {
            if (raw[frame] == 0) continue;
            int agree = 0;
            for (int i = Math.max(0, frame - 2); i < Math.min(raw.length, frame + 3); i++)
                if (raw[i] != 0 && Math.abs(raw[i] - raw[frame]) <= 1) agree++;
            if (agree >= 3) stable[frame] = raw[frame];
        }
        return stable;
    }

    private static double salience(ReconstructionLeadAudio.Track track, int frame, int pitch) {
        double level = track.frameLevel(frame);
        double fundamental = track.partialLevel(pitch, 1, frame);
        if (level < .002 || fundamental < Math.max(.0008, .09 * level)) return 0;
        double harmonic = .32 * track.partialLevel(pitch, 2, frame)
                + .21 * track.partialLevel(pitch, 3, frame) + .12 * track.partialLevel(pitch, 4, frame);
        return Math.min(1.3, (.70 * fundamental + harmonic) / level);
    }

    record Comparison(double voicedSeconds, double coveredSeconds, double coverage, double pitchAgreement,
                      double f0ComparableSeconds) {
        double score() {
            return coverage + pitchAgreement == 0 ? 0 : 2 * coverage * pitchAgreement / (coverage + pitchAgreement);
        }
    }

    record Selection(List<Note> notes, double periodicSeconds, double rms) {
        boolean supportsLead(double duration, double otherRms) {
            return otherRms == 0 || periodicSeconds >= Math.min(2, .15 * duration) && rms >= .035 * otherRms;
        }
    }

    static double rms(Path stem, double duration) throws IOException {
        double energy = 0;
        long samples = 0;
        for (double start = 0; start < duration; start += 1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Vocal level analysis cancelled");
            for (float sample : ReferenceAudio.read(stem, start, Math.min(1, duration - start)).samples()) {
                energy += (double) sample * sample;
                samples++;
            }
        }
        return samples == 0 ? 0 : Math.sqrt(energy / samples);
    }

    static Selection select(int part, ExternalTranscribers.PartNotes imported, ReconstructionLeadAudio.Track track,
                             double duration) {
        var metadata = imported.provenance().metadata();
        int[] rawPeriodic = periodicContour(track);
        int[] periodic = smooth(rawPeriodic);
        double floor = voicedFloor(track, periodic.length);
        int voiced = 0;
        double energy = 0;
        for (int frame = 0; frame < periodic.length; frame++) {
            double level = track.frameLevel(frame);
            energy += level * level;
            if (periodic[frame] != 0 && level >= floor) voiced++;
        }
        double rms = periodic.length == 0 ? 0 : Math.sqrt(energy / periodic.length);
        var raw = imported.notes();
        var gson = new com.google.gson.Gson();
        var tools = metadata.containsKey("vocalCandidates")
                ? gson.fromJson(metadata.get("vocalCandidates"), ExternalTranscribers.Evidence[].class)
                : new ExternalTranscribers.Evidence[]{imported.provenance()};
        int[] cleanup = periodic;
        var encoded = metadata.get("f0Midi10ms");
        if (encoded != null && !encoded.isBlank()) {
            var alternate = modelContour(track, encoded);
            for (int frame = 0; frame < rawPeriodic.length; frame++)
                if (rawPeriodic[frame] == 0) rawPeriodic[frame] = alternate[frame];
            cleanup = smooth(rawPeriodic);
        }

        // Use the same, model-independent reference for every candidate in this comparison.
        boolean hasSegmentedF0 = Arrays.stream(tools).anyMatch(tool -> tool.metadata().containsKey("f0Cents10ms"));
        int[] reference = hasSegmentedF0 ? periodic : cleanup;
        var report = new java.util.LinkedHashMap<String, Comparison>();
        var candidates = new java.util.LinkedHashMap<String, List<Note>>();
        var fusion = new ArrayList<ExternalTranscribers.RawNote>();
        int polyphonicTools = 0;
        int offset = 0;
        for (var tool : tools) {
            int end = offset + tool.noteCount();
            ReconstructionArtifacts.require(end >= offset && end <= raw.size(), "vocalCandidates", "invalid note count");
            var source = raw.subList(offset, end);
            offset = end;
            var notes = ReconstructionStemParts.notes(part, source, duration, true);
            var f0 = tool.metadata().get("f0Cents10ms");
            if (f0 != null) {
                candidates.put(tool.tool(), contourNotes(notes, f0, track.frames()));
            } else {
                candidates.put(tool.tool(), clean(part, notes, track, cleanup));
                fusion.addAll(source);
                polyphonicTools++;
            }
        }
        ReconstructionArtifacts.require(offset == raw.size(), "vocalCandidates", "note counts do not match snapshot");
        if (polyphonicTools > 1)
            candidates.put("fusion", clean(part, ReconstructionStemParts.notes(part, fusion, duration, true), track, cleanup));

        String best = null;
        double bestScore = -1;
        for (var candidate : candidates.entrySet()) {
            var measurement = compare(track, reference, candidate.getValue());
            report.put(candidate.getKey(), measurement);
            if (measurement.score() > bestScore + 1e-9) {
                best = candidate.getKey();
                bestScore = measurement.score();
            }
        }
        if (tools.length > 1 || hasSegmentedF0) {
            metadata.put("vocalSelection", best);
            metadata.put("vocalComparison", gson.toJson(report));
            metadata.put("vocalReference", hasSegmentedF0 ? "stem-periodicity" : "stem-periodicity-with-model-gap-fill");
        }
        return new Selection(candidates.getOrDefault(best, List.of()), voiced * HOP, rms);
    }

    private static List<Note> contourNotes(List<Note> notes, String encoded, int frames) {
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        ReconstructionArtifacts.require(bytes.length % 2 == 0, "f0Cents10ms", "expected 16-bit samples");
        var contour = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        return notes.stream().sorted(ReconstructionStages.NOTE_ORDER).map(note -> {
            if (note.end() - note.start() < .18) return note;
            int from = Math.min(contour.limit(), (int) Math.ceil((note.start() + .02) / HOP));
            int to = Math.min(Math.min(frames, contour.limit()), (int) Math.floor((note.end() - .02) / HOP));
            var samples = new ArrayList<ReconstructionBends.Sample>();
            for (int frame = from; frame < to; frame++) {
                int pitch = Short.toUnsignedInt(contour.get(frame));
                int cents = pitch - note.pitch() * 100;
                if (pitch == 0 || Math.abs(cents) > 120) return note;
                samples.add(new ReconstructionBends.Sample(frame * HOP, cents, .9));
            }
            var bend = ReconstructionBends.classify(note, samples);
            return bend == null ? note : note.withBend(bend);
        }).toList();
    }

    private static int[] modelContour(ReconstructionLeadAudio.Track track, String encoded) {
        var source = java.util.Base64.getDecoder().decode(encoded);
        var contour = new int[track.frames()];
        for (int frame = 0; frame < Math.min(source.length, contour.length); frame++) {
            int pitch = Byte.toUnsignedInt(source[frame]);
            if (pitch >= LOW && pitch <= HIGH && track.frameLevel(frame) >= .0015) contour[frame] = pitch;
        }
        return contour;
    }

    static Comparison compare(ReconstructionLeadAudio.Track track, int[] f0, List<Note> notes) {
        double threshold = voicedFloor(track, f0.length);
        int voiced = 0, covered = 0, comparable = 0, agrees = 0, next = 0;
        for (int frame = 0; frame < f0.length; frame++) {
            double time = frame * HOP;
            if (track.frameLevel(frame) < threshold) continue;
            voiced++;
            while (next < notes.size() && notes.get(next).end() <= time) next++;
            if (next >= notes.size() || notes.get(next).start() > time) continue;
            covered++;
            if (f0[frame] != 0) {
                comparable++;
                if (Math.abs(f0[frame] - notes.get(next).pitch()) <= 1) agrees++;
            }
        }
        return new Comparison(voiced * HOP, covered * HOP, voiced == 0 ? 0 : covered / (double) voiced,
                comparable == 0 ? 0 : agrees / (double) comparable, comparable * HOP);
    }

    private static double voicedFloor(ReconstructionLeadAudio.Track track, int frames) {
        var levels = java.util.stream.IntStream.range(0, frames).mapToDouble(track::frameLevel).sorted().toArray();
        double peak = levels.length == 0 ? 0 : levels[(int) ((levels.length - 1) * .99)];
        return Math.max(.0015, peak * Math.pow(10, -25.0 / 20));
    }

    static List<Note> clean(int part, List<Note> notes, ReconstructionLeadAudio.Track track, int[] f0) {
        double[] distribution = new double[HIGH + 1];
        int voiced = 0;
        for (int frame = 0; frame < f0.length; frame++) {
            if (f0[frame] == 0) continue;
            double level = track.frameLevel(frame);
            distribution[f0[frame]] += level * level;
            voiced++;
        }
        if (voiced < 15) return List.of();
        double energy = Arrays.stream(distribution).sum();
        int bottom = Math.max(LOW, percentile(distribution, .10 * energy) - 4);
        int top = Math.min(HIGH, percentile(distribution, .97 * energy) + 4);
        var seeded = new float[STATES][f0.length];
        for (var note : notes) {
            int pitch = note.pitch();
            if (pitch < bottom || pitch > top || note.end() - note.start() < .045) continue;
            int from = frame(note.start(), f0.length), to = frame(note.end(), f0.length);
            double support = 0;
            int observed = 0;
            for (int i = from; i < to; i++) {
                if (f0[i] == 0) continue;
                int difference = Math.abs(pitch - f0[i]);
                support += difference <= 1 ? 1 : difference == 2 ? .4 : .25 * salience(track, i, pitch);
                observed++;
            }
            if (observed == 0 || support < Math.max(2, .19 * observed)) continue;
            float reliability = (float) (support / observed);
            int state = pitch - LOW + 1;
            for (int i = from; i < to; i++) seeded[state][i] = Math.max(seeded[state][i], reliability);
        }

        int[] sustained = new int[f0.length];
        for (int start = 0, end; start < f0.length; start = end) {
            end = start + 1;
            while (end < f0.length && f0[start] != 0 && f0[end] == f0[start]) end++;
            if (end - start >= 12) Arrays.fill(sustained, start, end, f0[start]);
        }

        var previous = new double[STATES];
        Arrays.fill(previous, Double.NEGATIVE_INFINITY);
        previous[0] = 0;
        var current = new double[STATES];
        var back = new byte[f0.length][STATES];
        double voicedFloor = voicedFloor(track, f0.length);
        int sinceVoiced = 7;
        for (int frame = 0; frame < f0.length; frame++) {
            if (f0[frame] > 0 || track.frameLevel(frame) >= voicedFloor) sinceVoiced = 0;
            else sinceVoiced++;
            int best = maximum(previous);
            Arrays.fill(current, Double.NEGATIVE_INFINITY);
            choose(previous, current, back[frame], 0, best, 0);
            for (int pitch = bottom; pitch <= top; pitch++) {
                int state = pitch - LOW + 1;
                float reliability = seeded[state][frame];
                boolean candidate = reliability > 0 && sinceVoiced <= 7;
                if (!candidate && pitch != sustained[frame]) continue;
                double pitchSupport = salience(track, frame, pitch);
                double evidence = candidate ? .48 * reliability + .30 * pitchSupport - .16 : pitchSupport - .37;
                if (f0[frame] != 0) {
                    int interval = Math.abs(pitch - f0[frame]);
                    evidence += interval == 0 ? .25 : interval == 1 ? .12 : interval == 2 ? 0 : -.22;
                }
                choose(previous, current, back[frame], state, best, evidence);
                // Prefer a nearby step over a jump when both candidates have similar evidence.
                for (int prior = Math.max(1, state - 3); prior <= Math.min(STATES - 1, state + 3); prior++) {
                    double score = previous[prior] + evidence - .12 * Math.abs(prior - state);
                    if (score <= current[state]) continue;
                    current[state] = score;
                    back[frame][state] = (byte) prior;
                }
            }
            var exchange = previous;
            previous = current;
            current = exchange;
        }
        int state = maximum(previous);
        int[] path = new int[f0.length];
        for (int frame = f0.length - 1; frame >= 0; frame--) {
            path[frame] = state;
            state = back[frame][state];
        }

        var result = new ArrayList<Note>();
        for (int from = 0, to; from < path.length; from = to) {
            int selected = path[from];
            to = from + 1;
            while (to < path.length && path[to] == selected) to++;
            if (selected == 0 || to - from < 5) continue;
            int pitch = selected + LOW - 1;
            var note = new Note("vocal-" + part + "-" + from, part, from * HOP,
                    Math.min(track.duration(), to * HOP), pitch, 96, "audio-transcribed", .85);
            if (!result.isEmpty()) {
                var prior = result.getLast();
                double gap = note.start() - prior.end();
                boolean syllable = prior.pitch() == pitch && gap <= .035
                        && Arrays.stream(f0, frame(prior.end(), f0.length), from).anyMatch(f -> Math.abs(f - pitch) <= 1);
                if (gap <= .10 && (syllable || track.bridges(prior, note, List.of()))) {
                    if (prior.pitch() == pitch) {
                        result.set(result.size() - 1, prior.withTiming(prior.start(), note.end()));
                        continue;
                    }
                    result.set(result.size() - 1, prior.withTiming(prior.start(), note.start()));
                }
            }
            result.add(note);
        }
        return result;
    }

    private static int percentile(double[] distribution, double target) {
        for (int pitch = LOW; pitch < HIGH; pitch++) {
            target -= distribution[pitch];
            if (target <= 0) return pitch;
        }
        return HIGH;
    }

    private static int frame(double seconds, int length) {
        return Math.clamp((int) Math.round(seconds / HOP), 0, length);
    }

    private static int maximum(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    private static void choose(double[] previous, double[] current, byte[] back, int state, int best, double evidence) {
        double stay = previous[state], change = previous[best] - .65;
        current[state] = Math.max(stay, change) + evidence;
        back[state] = (byte) (stay >= change ? state : best);
    }
}
