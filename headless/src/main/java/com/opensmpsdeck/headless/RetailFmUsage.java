package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Summarizes note gates seen during a retail catalogue scan. */
final class RetailFmUsage {
    enum Role { bass, lead, chord, percussive }

    record Gate(String lane, int pitch, int start, int end) {}

    private RetailFmUsage() {}

    static InstrumentCatalogue.FmUsage summarize(List<Gate> gates, List<Gate> song) {
        var pitches = gates.stream().map(Gate::pitch).sorted().toList();
        var lengths = gates.stream().map(gate -> gate.end() - gate.start()).sorted().toList();
        int middle = gates.size() / 2;
        int low = pitches.getFirst(), high = pitches.getLast();
        int medianPitch = pitches.get(middle), medianFrames = lengths.get(middle);
        Role role;
        if ((long) high - low <= 2 && medianFrames <= 5) role = Role.percussive;
        else if (medianPitch <= 48) role = Role.bass;
        else if (chordAttacks(gates, song) * 2 >= gates.size()) role = Role.chord;
        else role = Role.lead;
        return new InstrumentCatalogue.FmUsage(gates.size(), low, high, medianPitch, medianFrames, role.name());
    }

    private static long chordAttacks(List<Gate> gates, List<Gate> song) {
        // Aligned pitched gates in other FM lanes suggest chord voicing.
        // Ignore octave doubling and the low register used for independent bass.
        var at = new HashMap<Integer, List<Gate>>();
        for (var gate : song) if (gate.pitch() > 48)
            at.computeIfAbsent(gate.start(), ignored -> new ArrayList<>()).add(gate);
        return gates.stream().filter(gate -> at.getOrDefault(gate.start(), List.of()).stream()
                .anyMatch(other -> !other.lane().equals(gate.lane())
                        && (other.pitch() - gate.pitch()) % 12 != 0
                        && Math.abs(other.end() - gate.end()) <= 2)).count();
    }
}
