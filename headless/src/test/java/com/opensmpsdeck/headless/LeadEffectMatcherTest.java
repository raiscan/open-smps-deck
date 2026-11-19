package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadEffectMatcherTest {
    private static final String LEAD = "lead";
    private static final String DOUBLE = "double";

    @TempDir Path directory;

    @Test void selectsNativeChorusOnHeldNotesAndPreservesUnrelatedMaterial() throws IOException {
        StudioProject dry = fixture();
        StudioProject wet = LeadEffectMatcher.candidate(dry, LEAD, DOUBLE, "chorus-medium");
        Path reference = render(wet, "chorus.wav");

        Path input = directory.resolve("input.json"), output = directory.resolve("matched.json");
        ProjectFiles.save(input, dry, null);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"catalog", "match-effects", input.toString(), reference.toString(),
                output.toString(), "--lead", LEAD, "--double", DOUBLE}, new PrintStream(stdout), new PrintStream(stderr)),
                stderr.toString());
        var report = JsonParser.parseString(stdout.toString()).getAsJsonObject();
        var matched = ProjectFiles.load(output).project();

        assertEquals("chorus-medium", report.get("selected").getAsString());
        assertEquals(dry.clips(), matched.clips());
        assertEquals(dry.sequences().get(2), matched.sequences().get(2));
        for (int track = 0; track < 2; track++) {
            assertEquals(dry.sequences().get(track).notes(), matched.sequences().get(track).notes());
        }
        assertTrue(matched.sequences().get(1).commands().stream()
                .anyMatch(command -> command.command() == NativeCommand.DETUNE));
        assertEquals(-1, Files.mismatch(reference, render(matched, "matched.wav")));
        assertNotEquals(-1, Files.mismatch(reference, render(dry, "comparison.wav")));
    }

    @Test void keepsDryArrangementWhenReferenceDoesNotSupportAnEffect() throws IOException {
        StudioProject dry = fixture();
        Path reference = render(dry, "dry.wav");

        var result = LeadEffectMatcher.match(dry, reference, LEAD, DOUBLE);

        assertEquals("baseline", result.report().selected());
        assertEquals(dry, result.project());
    }

    private static StudioProject fixture() {
        var voice = DemoProject.create().voices().getFirst();
        int span = 480;
        List<NoteEvent> leadNotes = IntStream.range(0, 24)
                .mapToObj(index -> new NoteEvent("lead-" + index, 4 + 20 * index, 15, 66 + index % 5)).toList();
        List<NoteEvent> doubleNotes = IntStream.range(0, 24)
                .mapToObj(index -> new NoteEvent("double-" + index, 4 + 20 * index, 15, 66 + index % 5)).toList();
        Sequence lead = new Sequence(LEAD, "Lead", "FM", span, voice.id(), 105, leadNotes);
        Sequence doubling = new Sequence(DOUBLE, "Lead double", "FM", span, voice.id(), 85, doubleNotes);
        Sequence bass = new Sequence("bass", "Bass", "FM", span, voice.id(), 92,
                List.of(new NoteEvent("bass-0", 0, 11, 44), new NoteEvent("bass-1", 260, 10, 43)));
        return new StudioProject(2, "Held lead fixture", "S3K", 15, 0, List.of(voice),
                List.of(lead, doubling, bass), List.of(new Clip("lead-clip", LEAD, "FM1", 0, span),
                new Clip("double-clip", DOUBLE, "FM2", 0, span), new Clip("bass-clip", "bass", "FM3", 0, span)));
    }

    private Path render(StudioProject project, String filename) throws IOException {
        Path output = directory.resolve(filename);
        WaveFile.render(ProjectCompiler.compileWithAssets(project), output, ProjectRenderTiming.presentationCount(project));
        return output;
    }
}
