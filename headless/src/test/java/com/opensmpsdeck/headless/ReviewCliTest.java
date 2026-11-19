package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReviewCliTest {
    @TempDir Path directory;

    @Test void listFiltersOpenNotesAndResolveRetainsAuthorshipAndReply() throws Exception {
        Path project = directory.resolve("song.smps.json");
        ProjectFiles.save(project, DemoProject.create(), null);
        String id = run("notes", "add", project.toString(), "--at", "1.25", "--end", "2.5", "--lane", "FM1",
                "--text", "Phrasing", "--author", "human").strip();
        assertTrue(run("notes", "list", project.toString(), "--open").contains(id));
        run("notes", "resolve", project.toString(), id, "--reply", "Changed attack", "--author", "agent");
        assertEquals("[]", run("notes", "list", project.toString(), "--open").strip());
        assertTrue(run("notes", "list", project.toString()).contains("Changed attack"));
        var note = ProjectFiles.load(project).project().notes().getFirst();
        assertEquals(1.25, note.startSeconds());
        assertEquals(2.5, note.endSeconds());
        assertEquals("human", note.author());
        assertEquals("agent", note.reply().author());
    }

    @Test void referenceAddUsesRelativePathsAndPreservesNegativeOffset() throws Exception {
        Path project = directory.resolve("song.smps.json"), wav = directory.resolve("guide.wav");
        ProjectFiles.save(project, DemoProject.create(), null);
        WaveFile.render(S3kDemo.song(), wav, 1);
        run("reference", "add", project.toString(), wav.toString(), "--offset", "-0.05", "--role", "mix");
        var reference = ProjectFiles.load(project).project().references().getFirst();
        assertEquals("guide.wav", reference.path());
        assertEquals(wav, reference.resolve(project));
        assertEquals(-0.05, reference.offsetSeconds());
        assertEquals("mix", reference.role());
    }

    private static String run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args, new PrintStream(out), new PrintStream(err)), err::toString);
        return out.toString();
    }
}
