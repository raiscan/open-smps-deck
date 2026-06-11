package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds time windows where a stem plays exactly one note (or one isolated drum hit). */
public final class MonophonicWindowFinder {

    public record Window(double startSec, double lengthSec, int midiPitch, double score) {}

    private static final double MIN_LENGTH_SEC = 0.25;

    private MonophonicWindowFinder() {}

    /** Melodic mode: windows where exactly one note sounds for ≥ 250 ms. */
    public static List<Window> find(List<NoteEvent> notes, TickTimeMapper map, int topK) {
        List<Window> candidates = new ArrayList<>();
        for (NoteEvent n : notes) {
            boolean isolated = notes.stream().noneMatch(o -> o != n
                    && o.startTick() < n.endTick() && o.endTick() > n.startTick());
            if (!isolated) continue;
            double start = map.secondsAt(n.startTick());
            double len = map.secondsAt(n.endTick()) - start;
            if (len < MIN_LENGTH_SEC) continue;
            // isolation margin: gap to the nearest neighbouring note
            double margin = notes.stream()
                    .filter(o -> o != n)
                    .mapToDouble(o -> Math.min(
                            Math.abs(map.secondsAt(o.startTick()) - map.secondsAt(n.endTick())),
                            Math.abs(map.secondsAt(n.startTick()) - map.secondsAt(o.endTick()))))
                    .min().orElse(1.0);
            candidates.add(new Window(start, len, n.pitch(),
                    len * Math.min(margin, 1.0) * (n.velocity() / 127.0)));
        }
        candidates.sort(Comparator.comparingDouble(Window::score).reversed());
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

    /**
     * Drum mode: hits of one class with no hit of any other class within ±isolationSec.
     * Returns all isolated hits ranked by velocity (caller takes the loudest).
     */
    public static List<Window> findDrumHits(List<NoteEvent> classHits, List<NoteEvent> otherHits,
                                            TickTimeMapper map, double isolationSec) {
        List<Window> out = new ArrayList<>();
        for (NoteEvent n : classHits) {
            double start = map.secondsAt(n.startTick());
            boolean isolated = otherHits.stream().noneMatch(o ->
                    Math.abs(map.secondsAt(o.startTick()) - start) < isolationSec);
            if (isolated) {
                out.add(new Window(start, map.secondsAt(n.endTick()) - start, n.pitch(),
                        n.velocity() / 127.0));
            }
        }
        out.sort(Comparator.comparingDouble(Window::score).reversed());
        return out;
    }
}
