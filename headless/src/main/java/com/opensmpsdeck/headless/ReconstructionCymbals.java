package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native-style noise substitutes and source-clock measurements for GM cymbals. */
final class ReconstructionCymbals {
    static final String COLLISION = "psg-cymbal-dac-collision";
    static final String QUIET = "psg-cymbal-quiet-stem";
    static final int PITCH = 117;
    static final int TONE_PERIOD = 16; // S3K PSG period at pitch 117.
    static final int NATIVE_LEVEL = 11;
    static final int HAT_LEVEL = NATIVE_LEVEL + 1;
    private static final double QUIET_RMS = .005; // −46 dBFS, near the top of the PSG cymbal range
    private static final double ATTACK_WINDOW = .060;
    private static final double ATTACK_LEAD = .012;
    private static final int HAT_BAND_HZ = 6_000;
    // Target about 6 dB above the isolated hat's excess, leaving the DAC transients in front.
    private static final double HAT_PRESENCE_GAIN = 2;

    // S3K alternates the terse PSG 01 with longer PSG 04/08 accents on
    // PSG3 in Angel Island and Hydrocity. Keep the short/long decay contrast.
    private static final Envelope CLOSED = new Envelope("psg-closed-cymbal", "Closed cymbal decay",
            Envelope.PSG_VOLUME, List.of(0, 4, 9, 0x83));
    private static final Envelope OPEN = new Envelope("psg-open-cymbal", "Open cymbal decay",
            Envelope.PSG_VOLUME, List.of(0, 0, 2, 3, 4, 5, 7, 9, 11, 0x83));

    static final int MAX_GATE = OPEN.bytes().size() - 1;

    private ReconstructionCymbals() {}

    static boolean eligible(int pitch) {
        return switch (pitch) {
            case 42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> true;
            default -> false;
        };
    }

    static boolean automatic(String reason) {
        return COLLISION.equals(reason) || QUIET.equals(reason);
    }

    static boolean voiced(ReconstructionAllocation.Entry entry) {
        return automatic(entry.reason()) || entry.laneType() == ReconstructionEdits.LaneType.NOISE
                && entry.role() == ReconstructionRoles.Role.drums && eligible(entry.note().pitch());
    }

    static boolean closedHat(int pitch) {
        return pitch == 42 || pitch == 44;
    }

    static Envelope envelope(int pitch) {
        return closedHat(pitch) ? CLOSED : OPEN;
    }

    static boolean isEnvelope(String id) {
        return CLOSED.id().equals(id) || OPEN.id().equals(id);
    }

    static int gate(int pitch) {
        return envelope(pitch).bytes().size() - 1;
    }

    static int detune(int period) {
        return period - TONE_PERIOD;
    }

    static ReconstructionVoices.Key key(ReconstructionStages.Note note) {
        return new ReconstructionVoices.Key(note.part(), note.pitch());
    }

    static boolean quiet(Double rms) {
        return rms != null && rms <= QUIET_RMS;
    }

    static Map<ReconstructionVoices.Key, Double> measure(ReconstructionStages.Score score) throws IOException {
        return measure(score, false);
    }

    private static Map<ReconstructionVoices.Key, Double> measure(ReconstructionStages.Score score, boolean fit)
            throws IOException {
        var inputs = score.inputs();
        if (inputs == null || inputs.fmOnly() || inputs.stems().isEmpty()) return Map.of();
        var byStem = new HashMap<Path, List<ReconstructionStages.Note>>();
        var mixed = new java.util.HashSet<Path>();
        mixed.add(Path.of(inputs.wav()).toAbsolutePath().normalize());
        for (var part : score.source().parts()) {
            String stem = inputs.stems().get(part.id());
            if (stem == null) continue;
            Path path = Path.of(stem).toAbsolutePath().normalize();
            if (part.drums()) byStem.putIfAbsent(path, new ArrayList<>());
            else mixed.add(path);
        }
        mixed.forEach(byStem::remove);
        if (byStem.isEmpty()) return Map.of();
        for (var note : score.notes()) {
            String stem = inputs.stems().get(note.part());
            if (stem == null) continue;
            var hits = byStem.get(Path.of(stem).toAbsolutePath().normalize());
            if (hits != null) hits.add(note);
        }
        var result = new HashMap<ReconstructionVoices.Key, Double>();
        for (var stem : byStem.entrySet()) measureStem(stem.getKey(), stem.getValue(), result, fit);
        return Map.copyOf(result);
    }

    private static void measureStem(Path stem, List<ReconstructionStages.Note> hits,
                                    Map<ReconstructionVoices.Key, Double> result, boolean fit) throws IOException {
        var isolated = new HashMap<ReconstructionVoices.Key, List<Double>>();
        hits.sort(Comparator.comparingDouble(ReconstructionStages.Note::start));
        double duration = ReferenceAudio.durationMicros(stem) / 1e6;
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            double t = hit.start();
            if (!eligible(hit.pitch()) || t < .060 || t + ATTACK_WINDOW > duration
                    || i > 0 && hits.get(i - 1).start() > t - .090
                    || i + 1 < hits.size() && hits.get(i + 1).start() < t + ATTACK_WINDOW) continue;
            isolated.computeIfAbsent(key(hit), ignored -> new ArrayList<>()).add(t);
        }
        for (var entry : isolated.entrySet()) {
            var onsets = entry.getValue();
            int count = Math.min(64, onsets.size());
            if (count < 3) continue;
            var levels = new double[count];
            boolean hat = fit && envelope(entry.getKey().drumPitch()) == CLOSED;
            for (int i = 0; i < count; i++) {
                // Sample across the part so repeated fills at the start don't set the song-wide gain.
                double t = onsets.get(i * (onsets.size() - 1) / (count - 1));
                var audio = ReferenceAudio.read(stem, t - .060, .060 + ATTACK_WINDOW);
                double attack = bandPower(audio.samples(), audio.sampleRate(), .060 - ATTACK_LEAD, ATTACK_WINDOW, hat);
                double bleed = bandPower(audio.samples(), audio.sampleRate(), .005, .040, hat);
                levels[i] = Math.sqrt(Math.max(0, attack - bleed)) * (hat ? HAT_PRESENCE_GAIN : 1);
            }
            java.util.Arrays.sort(levels);
            result.put(entry.getKey(), levels[count / 2]);
        }
    }

    private static double bandPower(float[] samples, int rate, double from, double length, boolean hat) {
        int start = (int) Math.round(from * rate);
        int end = Math.min(samples.length, start + (int) Math.round(length * rate));
        // Preserve the broad routing measurement. Weight the closed-hat fit toward the air band.
        double retain = Math.exp(-2 * Math.PI * (hat ? HAT_BAND_HZ : 2_000) / rate);
        double previous = samples[0], first = 0, second = 0, power = 0;
        for (int i = 0; i < end; i++) {
            double input = samples[i];
            double next = retain * (first + input - previous);
            second = retain * (second + next - first);
            first = next;
            previous = input;
            if (i >= start) power += second * second;
        }
        return power / (end - start);
    }

    static Map<Integer, Double> gains(ReconstructionStages.Score score, List<ReconstructionAllocation.Entry> entries)
            throws IOException {
        if (entries.stream().noneMatch(entry -> automatic(entry.reason()))) return Map.of();
        var levels = measure(score, true);
        var byPitch = new HashMap<Integer, List<Double>>();
        for (var entry : entries) {
            if (!automatic(entry.reason())) continue;
            Double rms = levels.get(key(entry.note()));
            if (rms != null) byPitch.computeIfAbsent(entry.note().pitch(), ignored -> new ArrayList<>()).add(rms);
        }
        var gains = new java.util.TreeMap<Integer, Double>();
        for (var entry : byPitch.entrySet()) {
            var targets = entry.getValue();
            targets.sort(Double::compare);
            int level = nearestLevel(entry.getKey(), targets.get(targets.size() / 2));
            gains.put(entry.getKey(), Math.pow(10, (level - NATIVE_LEVEL) / 10.0));
        }
        return gains;
    }

    private static int nearestLevel(int pitch, double rms) {
        double[] powers = envelope(pitch) == CLOSED ? Calibration.CLOSED_LEVELS : Calibration.OPEN_LEVELS;
        double target = Math.log(Math.max(1e-12, rms * rms));
        int best = 1; // Zero is hardware silence.
        for (int level = 2; level <= 15; level++) {
            if (Math.abs(Math.log(powers[level]) - target) < Math.abs(Math.log(powers[best]) - target))
                best = level;
        }
        return best;
    }

    private static final class Calibration {
        static final double[] CLOSED_LEVELS = render(CLOSED);
        static final double[] OPEN_LEVELS = render(OPEN);

        private static double[] render(Envelope envelope) {
            int spacing = 16;
            var sequences = new ArrayList<Sequence>();
            var clips = new ArrayList<Clip>();
            for (int level = 1; level <= 15; level++) {
                String id = "level-" + level;
                int ticks = envelope.bytes().size() - 1;
                var commands = new ArrayList<CommandEvent>();
                commands.add(new CommandEvent("noise", 0, NativeCommand.PSG_NOISE,
                        List.of(ReconstructionVoicing.PSG_PERCUSSION.noiseByte())));
                if (envelope == CLOSED) {
                    // Keep the fixed 14 kHz fit reference so clock edits do not move the attack level.
                    // The selected two-rate voicing adds one step to this fit in assembly.
                    commands.add(new CommandEvent("detune", 0, NativeCommand.DETUNE, List.of(detune(8) & 255)));
                }
                sequences.add(new Sequence(id, id, "PSG", ticks, null, level << 3,
                        List.of(new NoteEvent("hit", 0, ticks, PITCH)),
                        commands,
                        "RESET", envelope.id(), null));
                clips.add(new Clip(id, id, "PSG3", level * spacing, ticks));
            }
            var project = new StudioProject(4, "PSG calibration", "S3K", 30, 0, List.of(),
                    sequences, clips, List.of(envelope));
            int frames = spacing * 16;
            float[] audio = new float[frames * 800];
            int[] frame = {0};
            try {
                S3kRenderer.render(ProjectCompiler.compileWithAssets(project), frames, pcm -> {
                    int offset = frame[0]++ * 800;
                    for (int i = 0; i < 800; i++) audio[offset + i] = (pcm[2 * i] + pcm[2 * i + 1]) / 65536f;
                });
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            double[] result = new double[16];
            for (int level = 1; level <= 15; level++)
                result[level] = bandPower(audio, 48_000, level * spacing / 60.0 - ATTACK_LEAD, ATTACK_WINDOW,
                        envelope == CLOSED);
            return result;
        }
    }
}
