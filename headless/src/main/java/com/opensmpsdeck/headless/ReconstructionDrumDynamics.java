package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

/** Band-limited attack levels relative to each drum class in an isolated stem. */
record ReconstructionDrumDynamics(int part, @Nullable List<Double> span, @Nullable Boolean levels, @Nullable Double dropBelowDb) {
    private static final double PRE = .012;
    private static final double POST = .050;
    private static final double MIN_RMS = 1e-6;

    private enum Band {
        KICK(35, 160), SNARE(200, 4500), TOM(75, 650), HAT(5000, 14000), OTHER(100, 8000);

        final int low;
        final int high;

        Band(int low, int high) {
            this.low = low;
            this.high = high;
        }

        static Band forPitch(int pitch) {
            if (ReconstructionCymbals.eligible(pitch)) return HAT;
            return switch (pitch) {
                case 35, 36 -> KICK;
                case 37, 38, 39, 40 -> SNARE;
                case 41, 43, 45, 47, 48, 50 -> TOM;
                default -> OTHER;
            };
        }
    }

    boolean fitLevels() { return Boolean.TRUE.equals(levels); }

    void validate(ReconstructionStages.Score score) throws IOException {
        ReconstructionArrangement.part(score, part, true);
        ReconstructionArrangement.span(span);
        require(fitLevels() || dropBelowDb != null, "arrangement.drumDynamics", "enable levels and/or set dropBelowDb");
        require(dropBelowDb == null || dropBelowDb < 0 && dropBelowDb >= -120,
                "arrangement.drumDynamics.dropBelowDb", "expected negative dB relative to the class median");
        var inputs = score.inputs();
        String stem = inputs.stems().get(part);
        require(stem != null, "arrangement.drumDynamics", "part needs a mapped drum stem");
        var path = Path.of(stem);
        require(!java.nio.file.Files.isSameFile(path, Path.of(inputs.wav())), "arrangement.drumDynamics",
                "map an isolated drum stem");
    }

    Map<String, Double> measure(ReconstructionStages.Score score, List<Note> notes) throws IOException {
        var selected = notes.stream().filter(note -> note.part() == part && ReconstructionArrangement.covers(span, note.start())).toList();
        if (selected.isEmpty()) return Map.of();
        var path = Path.of(score.inputs().stems().get(part));
        var audio = ReferenceAudio.evidenceRange(path, 0, score.durationUs() / 1e6 + POST);
        return relativeLevels(audio.samples(), audio.rate(), selected);
    }

    static Map<String, Double> relativeLevels(float[] samples, int rate, List<Note> notes) {
        var measured = new HashMap<String, Double>();
        var byBand = new java.util.EnumMap<Band, List<Double>>(Band.class);
        for (var note : notes) {
            if (note.start() * rate >= samples.length) continue;
            var band = Band.forPitch(note.pitch());
            double rms = attackRms(samples, rate, note.start(), band);
            measured.put(note.id(), rms);
            byBand.computeIfAbsent(band, ignored -> new ArrayList<>()).add(rms);
        }
        var medians = new java.util.EnumMap<Band, Double>(Band.class);
        byBand.forEach((band, hits) -> {
            hits.sort(Double::compare);
            medians.put(band, Math.max(MIN_RMS, hits.get(hits.size() / 2)));
        });
        var levels = new TreeMap<String, Double>();
        for (var note : notes) {
            Double rms = measured.get(note.id());
            if (rms == null) continue;
            double median = medians.get(Band.forPitch(note.pitch()));
            double relative = rms < MIN_RMS ? -120 : 20 * Math.log10(rms / median);
            levels.put(note.id(), Math.max(-120, relative));
        }
        return levels;
    }

    private static double attackRms(float[] samples, int rate, double onset, Band band) {
        int start = (int) Math.round(onset * rate);
        int from = Math.max(0, start - (int) Math.round(PRE * rate));
        int to = Math.min(samples.length, start + (int) Math.round(POST * rate));
        double lowKeep = Math.exp(-Math.TAU * band.low / rate);
        double highKeep = Math.exp(-Math.TAU * band.high / rate);
        double slow = samples[from], fast = slow, power = 0;
        for (int i = from; i < to; i++) {
            slow = lowKeep * slow + (1 - lowKeep) * samples[i];
            fast = highKeep * fast + (1 - highKeep) * samples[i];
            double filtered = fast - slow;
            power += filtered * filtered;
        }
        return Math.sqrt(power / Math.max(1, to - from));
    }

    static double gain(double db) {
        // Share PCM variants across hits. Keep gain changes within the useful native range.
        double quantized = Math.clamp(2.0 * Math.round(db / 2), -10, 2);
        return Math.pow(10, quantized / 20);
    }
}
