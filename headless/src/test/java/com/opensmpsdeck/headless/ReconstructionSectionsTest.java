package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionSections.Level;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionSectionsTest {
    @TempDir Path work;

    @Test void proposesNestedRepeatFamiliesFromTheGrid() throws Exception {
        writeScore(24, 2);
        var form = ReconstructionSections.load(work);
        var sections = form.sections().stream().filter(span -> span.level() == Level.section).toList();
        assertEquals(List.of("A", "B", "A"), sections.stream().map(ReconstructionSections.Section::label).toList());
        assertEquals(List.of(0.0, 16.0, 32.0), sections.stream().map(ReconstructionSections.Section::sourceStart).toList());
        assertEquals(form.automatic(), form.sections());
        for (var span : form.sections()) {
            assertEquals(span.sourceStart(), span.projectStart());
            assertEquals(span.sourceStart() / 2 + 1, span.startBar());
            assertTrue(span.startConfidence() > 0 && span.startConfidence() <= 1);
            if (span.level() == Level.section) continue;
            var parent = form.select(span.parentId());
            assertTrue(span.sourceStart() >= parent.sourceStart() && span.sourceEnd() <= parent.sourceEnd());
            if (span.level() == Level.period) assertTrue(form.sections().stream()
                    .filter(child -> span.id().equals(child.parentId()) && child.level() == Level.phrase).count() >= 2);
        }
        assertFalse(Files.exists(work.resolve(ReconstructionSections.FILE)));
    }

    @Test void canSplitMergeAndRetainSourceAnchorsAcrossGridAndDurationChanges() throws Exception {
        writeScore(24, 2);
        var initial = ReconstructionSections.load(work);
        var split = change("set", """
                [
                  {"id":"section-1","level":"section","name":"Intro","author":"human","reason":"Split before lead","sourceStart":0,"sourceEnd":8},
                  {"id":"theme","level":"section","name":"Theme","author":"agent","reason":"Lead entry","sourceStart":8,"sourceEnd":16}
                ]
                """);
        assertEquals(8, split.select("Intro").sourceEnd());
        assertEquals(8, split.select("theme").sourceStart());
        assertEquals(initial.automatic(), split.automatic());

        var merged = change("set", """
                [
                  {"id":"section-1","level":"section","name":"Intro","author":"human","reason":"Merge with lead","sourceStart":0,"sourceEnd":16},
                  {"id":"theme","author":"human","reason":"Merged into intro","removed":true}
                ]
                """);
        assertEquals(16, merged.select("Intro").sourceEnd());
        assertThrows(IllegalArgumentException.class, () -> merged.select("theme"));

        writeScore(24, 1.875);
        var shifted = ReconstructionSections.load(work);
        assertEquals(16, shifted.select("Intro").sourceEnd());
        assertTrue(shifted.issues().stream().anyMatch(issue -> issue.id().equals("section-1")
                && issue.detail().contains("anchor shifted")));

        writeScore(4, 2);
        var shortened = ReconstructionSections.load(work);
        assertThrows(IllegalArgumentException.class, () -> shortened.select("Intro"));
        assertEquals(2, shortened.issues().size());
        assertTrue(Files.readString(work.resolve(ReconstructionSections.FILE)).contains("Merged into intro"));
    }

    private ReconstructionSections.Result change(String action, String json) throws Exception {
        return ReconstructionSections.run(work, action, JsonParser.parseString(json));
    }

    private void writeScore(int bars, double length) throws Exception {
        var grid = new ArrayList<ReconstructionStructure.Bar>();
        var notes = new ArrayList<ReconstructionStages.Note>();
        for (int i = 0; i < bars; i++) {
            double start = i * length;
            grid.add(new ReconstructionStructure.Bar("b" + (i + 1), start, (i + 1) * length, true));
            int pitch = i >= 8 && i < 16 ? 73 : 60;
            notes.add(new ReconstructionStages.Note("n" + i, 0, start, start + .7 * length, pitch, 90, "midi", 1));
            if (i >= 8 && i < 16) for (int beat = 0; beat < 8; beat++) {
                double hit = start + beat * length / 8;
                notes.add(new ReconstructionStages.Note("d" + i + "-" + beat, 1, hit, hit + .1, 36, 90, "midi", 1));
            }
        }
        var inputs = new ReconstructionStages.Inputs("test.mid", "test.wav", "catalogue.json", "Synthetic", 30,
                false, false, false, false, Map.of(), null, 0, null, false);
        var source = new MidiScore(List.of(new MidiScore.Part(0, "Lead", 81, false),
                new MidiScore.Part(1, "Drums", 0, true)), List.of(), List.of());
        long duration = Math.round(bars * length * 1e6);
        var score = new ReconstructionStages.Score(1, duration, (int) Math.ceil(duration * 60 / 1e6), 60,
                inputs, grid, source, Map.of(0, bars, 1, notes.size() - bars), notes, List.of(), List.of(), null, null);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
    }
}
