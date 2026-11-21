package com.opensmpsdeck.headless;

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
        return contour(track, null);
    }

    private static int[] contour(ReconstructionLeadAudio.Track track, int[] alternate) {
        int[] raw = new int[track.frames()];
        var distances = new double[128];
        for (int frame = 0; frame < raw.length; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("vocal contour cancelled");
            int pitch = track.periodicPitch(frame, distances);
            raw[frame] = pitch != 0 || alternate == null ? pitch : alternate[frame];
        }
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

    record Comparison(double voicedSeconds, double coveredSeconds, double coverage, double pitchAgreement) {
        double score() {
            return coverage + pitchAgreement == 0 ? 0 : 2 * coverage * pitchAgreement / (coverage + pitchAgreement);
        }
    }

    static List<Note> select(int part, ExternalTranscribers.PartNotes imported, ReconstructionLeadAudio.Track track,
                             double duration) {
        var encoded = imported.provenance().metadata().get("f0Midi10ms");
        int[] f0 = contour(track, encoded == null || encoded.isBlank() ? null : modelContour(track, encoded));
        var raw = imported.notes();
        var metadata = imported.provenance().metadata();
        if (!metadata.containsKey("vocalFirstNoteCount")) {
            return clean(part, ReconstructionStemParts.notes(part, raw, duration, true), track, f0);
        }
        int split = Integer.parseInt(metadata.get("vocalFirstNoteCount"));
        ReconstructionArtifacts.require(split >= 0 && split <= raw.size(), "vocalFirstNoteCount", "outside the note snapshot");
        var tools = imported.provenance().tool().split(",", -1);
        ReconstructionArtifacts.require(tools.length == 2, "vocalCandidates", "expected two transcriber names");
        var groups = List.of(raw.subList(0, split), raw.subList(split, raw.size()), raw);
        var report = new java.util.LinkedHashMap<String, Comparison>();
        var bestNotes = List.<Note>of();
        String bestName = null;
        double bestScore = -1;
        for (int i = 0; i < groups.size(); i++) {
            String name = i == 2 ? "fusion" : tools[i];
            var notes = clean(part, ReconstructionStemParts.notes(part, groups.get(i), duration, true), track, f0);
            var measurement = compare(track, f0, notes);
            report.put(name, measurement);
            if (measurement.score() > bestScore + 1e-9) {
                bestScore = measurement.score();
                bestNotes = notes;
                bestName = name;
            }
        }
        metadata.put("vocalSelection", bestName);
        metadata.put("vocalComparison", new com.google.gson.Gson().toJson(report));
        return bestNotes;
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
                comparable == 0 ? 0 : agrees / (double) comparable);
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
