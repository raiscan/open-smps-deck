package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionStages.Note;
import static com.opensmpsdeck.headless.ReconstructionStructure.Bar;

/** The bar-local grid in beat fractions, shared by score inspection and role plans. */
public final class ReconstructionAttackGrid {
    static final int STEPS = 16;

    private ReconstructionAttackGrid() {}

    static long roundedStep(Bar bar, double time, ReconstructionBeatMap beatMap) {
        if (beatMap == null) return Math.round(bar.fraction(time) * STEPS);
        double start = beatMap.index(bar.start());
        double fraction = (beatMap.index(time) - start) / (beatMap.index(bar.end()) - start);
        return Math.round(fraction * STEPS);
    }

    static int attackStep(Bar bar, double time, ReconstructionBeatMap beatMap) {
        return Math.clamp(roundedStep(bar, time, beatMap), 0, STEPS - 1);
    }

    static Set<String> chordNotes(List<Bar> bars, Map<String, List<Note>> byBar, ReconstructionBeatMap beatMap) {
        var chords = new HashSet<String>();
        for (var bar : bars) {
            var firstAttacks = new HashMap<Integer, String[]>();
            for (var note : byBar.get(bar.id())) {
                int step = attackStep(bar, note.start(), beatMap);
                String[] steps = firstAttacks.computeIfAbsent(note.part(), part -> new String[STEPS]);
                if (steps[step] == null) {
                    steps[step] = note.id();
                } else {
                    chords.add(steps[step]);
                    chords.add(note.id());
                }
            }
        }
        return chords;
    }

    /** Returns IDs of the prepared notes in bracketed attack cells. */
    public static Set<String> chordNotes(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var structure = ReconstructionStages.readStructure(work.resolve("structure.json"), score);
        return chordNotes(structure.bars(), ReconstructionRoles.byBar(structure.bars(), structure.notes()),
                score.usesBeatGrid() ? score.beatMap() : null);
    }
}
