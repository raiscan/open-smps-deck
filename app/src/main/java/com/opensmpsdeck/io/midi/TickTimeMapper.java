package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Converts absolute MIDI ticks to seconds using a tempo map. */
public final class TickTimeMapper {

    private static final int DEFAULT_US_PER_QUARTER = 500000; // 120 BPM

    private final int ppq;
    private final long[] ticks;
    private final double[] seconds;
    private final int[] usPerQuarter;

    public TickTimeMapper(int ppq, List<MidiStem.TempoEvent> tempoMap) {
        this.ppq = ppq;
        List<MidiStem.TempoEvent> map = new ArrayList<>(tempoMap);
        map.sort(Comparator.comparingLong(MidiStem.TempoEvent::tick));
        if (map.isEmpty() || map.get(0).tick() > 0) {
            map.add(0, new MidiStem.TempoEvent(0, DEFAULT_US_PER_QUARTER));
        }
        ticks = new long[map.size()];
        seconds = new double[map.size()];
        usPerQuarter = new int[map.size()];
        double acc = 0;
        for (int i = 0; i < map.size(); i++) {
            ticks[i] = map.get(i).tick();
            usPerQuarter[i] = map.get(i).microsecondsPerQuarter();
            if (i > 0) {
                acc += (ticks[i] - ticks[i - 1]) * usPerQuarter[i - 1] / 1e6 / ppq;
            }
            seconds[i] = acc;
        }
    }

    public double secondsAt(long tick) {
        int i = ticks.length - 1;
        while (i > 0 && ticks[i] > tick) i--;
        return seconds[i] + (tick - ticks[i]) * usPerQuarter[i] / 1e6 / ppq;
    }
}
