package com.opensmpsdeck.io.midi;

import java.util.*;

/** Maps GM drum pitches to DAC sample slots and PSG noise hits. */
public final class GmDrumMapper {

    public enum DrumTarget {
        DAC_KICK(0, 3), DAC_SNARE(1, 2), DAC_TOM(2, 1),
        NOISE_SHORT(-1, 0), NOISE_LONG(-1, 0), DROP(-1, 0);

        public final int dacSlot;     // -1 = not a DAC target
        public final int priority;    // for simultaneous-hit resolution

        DrumTarget(int dacSlot, int priority) {
            this.dacSlot = dacSlot;
            this.priority = priority;
        }

        public boolean isDac() { return dacSlot >= 0; }
        public boolean isNoise() { return this == NOISE_SHORT || this == NOISE_LONG; }
    }

    /** Editable pitch → target table (dialog mutates a copy of the default). */
    public record Mapping(Map<Integer, DrumTarget> byPitch) {
        public DrumTarget targetFor(int pitch) {
            return byPitch.getOrDefault(pitch, DrumTarget.DROP);
        }
    }

    public record DrumHit(int startStep, int lengthSteps, DrumTarget target) {}

    public record SplitResult(List<DrumHit> dacHits, List<DrumHit> noiseHits,
                              List<Integer> droppedPitches) {}

    private GmDrumMapper() {}

    public static Mapping defaultMapping() {
        Map<Integer, DrumTarget> m = new HashMap<>();
        m.put(35, DrumTarget.DAC_KICK);   m.put(36, DrumTarget.DAC_KICK);
        m.put(38, DrumTarget.DAC_SNARE);  m.put(40, DrumTarget.DAC_SNARE);
        for (int tom : new int[]{41, 43, 45, 47, 48, 50}) m.put(tom, DrumTarget.DAC_TOM);
        m.put(42, DrumTarget.NOISE_SHORT); m.put(44, DrumTarget.NOISE_SHORT);
        m.put(46, DrumTarget.NOISE_LONG);
        for (int cym : new int[]{49, 51, 55, 57, 59}) m.put(cym, DrumTarget.NOISE_LONG);
        return new Mapping(m);
    }

    public static SplitResult split(List<NoteQuantizer.QuantizedNote> notes, Mapping mapping) {
        // step → best DAC hit (priority resolution)
        Map<Integer, DrumHit> dacByStep = new TreeMap<>();
        List<DrumHit> noise = new ArrayList<>();
        Set<Integer> dropped = new TreeSet<>();

        for (NoteQuantizer.QuantizedNote n : notes) {
            DrumTarget t = mapping.targetFor(n.pitch());
            if (t == DrumTarget.DROP) {
                dropped.add(n.pitch());
            } else if (t.isDac()) {
                DrumHit hit = new DrumHit(n.startStep(), n.lengthSteps(), t);
                dacByStep.merge(n.startStep(), hit,
                        (a, b) -> a.target().priority >= b.target().priority ? a : b);
            } else {
                noise.add(new DrumHit(n.startStep(),
                        t == DrumTarget.NOISE_SHORT ? 1 : n.lengthSteps(), t));
            }
        }
        noise.sort(Comparator.comparingInt(DrumHit::startStep));
        return new SplitResult(List.copyOf(dacByStep.values()), List.copyOf(noise),
                List.copyOf(dropped));
    }
}
