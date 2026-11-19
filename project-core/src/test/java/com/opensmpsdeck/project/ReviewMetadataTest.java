package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewMetadataTest {
    @TempDir Path directory;

    @Test void referenceAndNoteRoundTripAndSurviveArrangementEdits() {
        var wav = directory.resolve("mix.wav");
        var path = directory.resolve("song.smps.json");
        var reference = new AudioReference("mix", "Mix", "mix.wav", -0.125, -3.5, "mix");
        var absolute = new AudioReference("stem", "Stem", wav.toString(), 0, 0, null);
        var note = new ReviewNote("note", 0.04, 1.125, "FM1", null, "Check phrasing", "human",
                "open", Instant.EPOCH.toString(), null);
        var project = ProjectEdits.addReference(DemoProject.create(), reference);
        project = ProjectEdits.addReference(project, absolute);
        project = ProjectEdits.addReviewNote(project, note);
        project = ProjectEdits.deleteClip(project, project.clips().getFirst().id());

        assertEquals(List.of(reference, absolute), project.references());
        assertEquals(List.of(note), project.notes());
        assertEquals(project, ProjectFormat.read(ProjectFormat.write(project)));
        assertEquals(wav, reference.resolve(path));
        assertEquals(wav, absolute.resolve(path));
    }

    @Test void everyExampleRetainsItsVersionAndOmitsEmptyMetadata() throws Exception {
        try (var examples = Files.walk(Path.of("../examples"))) {
            for (var file : examples.filter(p -> p.toString().endsWith(".smps.json")).toList()) {
                var project = ProjectFiles.load(file).project();
                assertTrue(project.references().isEmpty(), file.toString());
                assertTrue(project.notes().isEmpty(), file.toString());
                var saved = ProjectFormat.write(project);
                assertFalse(saved.contains("\"references\":"), file.toString());
                assertFalse(saved.lines().anyMatch(line -> line.startsWith("  \"notes\":")), file.toString());
                assertEquals(project, ProjectFormat.read(saved), file.toString());
            }
        }
    }
}
