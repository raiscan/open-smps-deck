package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoteHandoffTest {
    @Test void arrangementBlockRoundTripsAndLooksUpItsIds(@TempDir Path folder) throws Exception {
        var project = DemoProject.create();
        var path = folder.resolve("it's a song.smps.json");
        ProjectFiles.save(path, project, null);
        var clip = project.clips().getFirst();
        var sequence = project.sequences().stream().filter(s -> s.id().equals(clip.sequenceId())).findFirst().orElseThrow();
        var notes = sequence.notes().stream().limit(3).toList();
        var text = ArrangementNoteContext.copy(path, project, false, sequence.id(), List.of(clip.id()), notes);
        var block = NoteHandoff.parse(text);
        assertEquals(text, block.text());
        assertEquals(path.toString(), block.project().path());
        assertEquals(notes.stream().map(n -> n.id()).toList(), block.notes().stream().map(NoteHandoff.Note::id).toList());
        assertEquals(clip.lane(), block.notes().getFirst().lane());
        assertEquals(clip.startTick() + notes.getFirst().tick(), block.notes().getFirst().tick());
        assertEquals(3, block.context().selectedCount());
        assertTrue(block.command().contains("'\\''"));

        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        var ids = block.notes().stream().map(NoteHandoff.Note::id).toList();
        assertEquals(0, Main.run(new String[]{"describe-notes", path.toString(), "--sequence", block.project().sequence(),
                "--clip", NoteHandoff.JSON.toJson(block.project().clip()), "--notes", NoteHandoff.JSON.toJson(ids),
                "--revision", block.project().revision()}, stream, stream), output::toString);
        var resolved = JsonParser.parseString(output.toString()).getAsJsonObject();
        assertFalse(resolved.get("stale").getAsBoolean());
        assertEquals(3, resolved.getAsJsonArray("selected").size());
        assertEquals(0, resolved.getAsJsonArray("missingIds").size());
    }

    @Test void clipboardCapPreservesBothEndsAndIdsWithPunctuation() {
        var project = DemoProject.create();
        var sequence = project.sequences().getFirst();
        var notes = new ArrayList<com.opensmpsdeck.project.NoteEvent>();
        for (int i = 0; i < 12; i++) notes.add(new com.opensmpsdeck.project.NoteEvent("note,'" + i, i, 1, 60));
        var block = NoteHandoff.parse(ArrangementNoteContext.copy(Path.of("fixture.smps.json"), project, true,
                sequence.id(), List.of(), notes));
        assertEquals(8, block.notes().size());
        assertEquals(12, block.context().selectedCount());
        assertEquals(4, block.context().omittedCount());
        assertEquals("note,'0", block.notes().getFirst().id());
        assertEquals("note,'11", block.notes().getLast().id());
        assertEquals(List.of("note,'0"), NoteHandoff.ids("[\"note,'0\"]"));
    }
}
